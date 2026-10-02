package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.infra.web.RequestContextPropagation;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.OutboxEventId;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.meldtech.platform.shared.kernel.outbox.AggregateReference;
import org.meldtech.platform.shared.kernel.outbox.OutboxMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class OutboxFailureInjectionIntegrationTest extends OutboxPostgreSqlIntegrationTestSupport {

    private static final UUID TENANT_VALUE =
            UUID.fromString("01950f47-6000-7000-8000-000000000001");
    private static final TenantId TENANT = TenantId.parse(TENANT_VALUE.toString());
    private static final CorrelationId CORRELATION =
            CorrelationId.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    private static final ActorContext ACTOR =
            ActorContext.tenantWorkforce(
                    new ActorId("outbox-fault-test"),
                    TENANT,
                    CORRELATION,
                    SourceIp.parse("127.0.0.1"));
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void crashBeforeCommitLeavesNoBusinessChangeOrPublishableEvent() throws Exception {
        StepVerifier.create(writeEvent(eventId(1), true))
                .expectError(InjectedFailure.class)
                .verify();

        assertThat(queryInt("SELECT count(*) FROM delivery.outbox_atomicity_probe")).isZero();
        assertThat(queryInt("SELECT count(*) FROM outbox.outbox_event")).isZero();
        assertThat(claim(10, "relay-a", Instant.now().plusSeconds(30))).isEmpty();
    }

    @Test
    void crashAfterCommitLeavesAPendingRowForTheNextRelayTick() throws Exception {
        StepVerifier.create(commitEvent(eventId(2))).verifyComplete();

        assertThat(queryString("SELECT state FROM outbox.outbox_event")).isEqualTo("PENDING");
        assertThat(claim(10, "relay-a", Instant.now().plusSeconds(30)))
                .singleElement()
                .extracting(ClaimedOutboxEvent::eventId)
                .isEqualTo(eventId(2));
    }

    @Test
    void relayCrashMidBatchLeavesClaimsForReclamation() throws Exception {
        StepVerifier.create(commitEvent(eventId(3)).then(commitEvent(eventId(4)))).verifyComplete();
        List<ClaimedOutboxEvent> claimed = claim(10, "relay-a", Instant.now().minusSeconds(1));
        assertThat(claimed).hasSize(2);

        StepVerifier.create(
                        relayTransaction(
                                new StaleClaimReclaimer(databaseClient, mock(OutboxTelemetry.class))
                                        .reclaim()
                                        .then()))
                .verifyComplete();

        assertThat(
                        queryInt(
                                "SELECT count(*) FROM outbox.outbox_event "
                                        + "WHERE state = 'PENDING'"))
                .isEqualTo(2);
    }

    @Test
    void killedRelayLeavesNoStrandedOrTwicePublishedClaim() throws Exception {
        List<String> expected =
                java.util.stream.IntStream.rangeClosed(21, 26)
                        .mapToObj(OutboxFailureInjectionIntegrationTest::eventId)
                        .toList();
        Mono<Void> writes = Mono.empty();
        for (String eventId : expected) {
            writes = writes.then(commitEvent(eventId));
        }
        StepVerifier.create(writes).verifyComplete();

        List<ClaimedOutboxEvent> firstBatch =
                claim(expected.size(), "relay-killed", Instant.now().minusSeconds(1));
        List<String> publications = new ArrayList<>();
        RelayPublishStep firstRelay =
                publishStep(
                        event -> {
                            publications.add(event.eventId());
                            return Mono.just(new OutboxBrokerPublisher.BrokerConfirmation(true));
                        },
                        new DatabaseOutboxPublicationStore(databaseClient),
                        new ArrayList<>());
        for (ClaimedOutboxEvent confirmed : firstBatch.subList(0, 2)) {
            StepVerifier.create(relayTransaction(firstRelay.publishOne(confirmed)))
                    .verifyComplete();
        }

        StepVerifier.create(
                        relayTransaction(
                                new StaleClaimReclaimer(databaseClient, mock(OutboxTelemetry.class))
                                        .reclaim()
                                        .then()))
                .verifyComplete();
        List<ClaimedOutboxEvent> recovered =
                claim(expected.size(), "relay-replacement", Instant.now().plusSeconds(30));
        RelayPublishStep replacement =
                publishStep(
                        event -> {
                            publications.add(event.eventId());
                            return Mono.just(new OutboxBrokerPublisher.BrokerConfirmation(true));
                        },
                        new DatabaseOutboxPublicationStore(databaseClient),
                        new ArrayList<>());
        StepVerifier.create(
                        relayTransaction(
                                replacement.publishSequentially(Flux.fromIterable(recovered))))
                .verifyComplete();

        assertThat(publications).containsExactlyElementsOf(expected);
        assertThat(publications).doesNotHaveDuplicates();
        assertThat(
                        queryInt(
                                "SELECT count(*) FROM outbox.outbox_event "
                                        + "WHERE state = 'PUBLISHED'"))
                .isEqualTo(expected.size());
        assertThat(
                        queryInt(
                                "SELECT count(*) FROM outbox.outbox_event "
                                        + "WHERE state = 'CLAIMED'"))
                .isZero();
    }

    @Test
    void brokerOutageCannotRollBackTheCommittedBusinessWrite() throws Exception {
        StepVerifier.create(commitEvent(eventId(5))).verifyComplete();
        ClaimedOutboxEvent claimed = claim(1, "relay-a", Instant.now().plusSeconds(30)).getFirst();
        RelayPublishStep step =
                publishStep(
                        event -> Mono.error(new InjectedFailure()),
                        new DatabaseOutboxPublicationStore(databaseClient),
                        new ArrayList<>());

        StepVerifier.create(relayTransaction(step.publishOne(claimed))).verifyComplete();

        assertThat(queryInt("SELECT count(*) FROM delivery.outbox_atomicity_probe")).isEqualTo(1);
        assertThat(queryString("SELECT state FROM outbox.outbox_event")).isEqualTo("PENDING");
        assertThat(queryInt("SELECT attempt_count FROM outbox.outbox_event")).isEqualTo(1);
    }

    @Test
    void brokerAcceptanceBeforeStateUpdateCausesSafeRedelivery() throws Exception {
        StepVerifier.create(commitEvent(eventId(6))).verifyComplete();
        ClaimedOutboxEvent firstClaim =
                claim(1, "relay-a", Instant.now().minusSeconds(1)).getFirst();
        AtomicInteger deliveries = new AtomicInteger();
        DatabaseOutboxPublicationStore databaseStore =
                new DatabaseOutboxPublicationStore(databaseClient);
        OutboxPublicationStore crashBeforeStateUpdate =
                new OutboxPublicationStore() {
                    @Override
                    public Mono<Void> markPublished(ClaimedOutboxEvent event, Instant publishedAt) {
                        return Mono.error(new InjectedFailure());
                    }

                    @Override
                    public Mono<FailureTransition> recordFailure(
                            ClaimedOutboxEvent event,
                            PublicationFailureReason reason,
                            Instant nextAttemptAt) {
                        return databaseStore.recordFailure(event, reason, nextAttemptAt);
                    }
                };
        RelayPublishStep interrupted =
                publishStep(
                        event -> {
                            deliveries.incrementAndGet();
                            return Mono.just(new OutboxBrokerPublisher.BrokerConfirmation(true));
                        },
                        crashBeforeStateUpdate,
                        new ArrayList<>());

        StepVerifier.create(relayTransaction(interrupted.publishOne(firstClaim))).verifyComplete();
        executeAsClusterOwner(
                "UPDATE outbox.outbox_event SET next_attempt_at = CURRENT_TIMESTAMP "
                        + "WHERE outbox_event_id = '"
                        + eventId(6)
                        + "'");
        ClaimedOutboxEvent secondClaim =
                claim(1, "relay-b", Instant.now().plusSeconds(30)).getFirst();
        RelayPublishStep recovered =
                publishStep(
                        event -> {
                            deliveries.incrementAndGet();
                            return Mono.just(new OutboxBrokerPublisher.BrokerConfirmation(true));
                        },
                        databaseStore,
                        new ArrayList<>());
        StepVerifier.create(relayTransaction(recovered.publishOne(secondClaim))).verifyComplete();

        assertThat(deliveries).hasValue(2);
        assertThat(queryString("SELECT state FROM outbox.outbox_event")).isEqualTo("PUBLISHED");
    }

    @Test
    void consumerCrashBeforeCommitRollsBackBothEffectAndGuard() throws Exception {
        ConsumedEvent event = consumedEvent(eventId(7));
        ProcessedEventGuard guard =
                new ProcessedEventGuard(
                        ConsumerModule.DELIVERY, databaseClient, mock(OutboxTelemetry.class));

        StepVerifier.create(
                        consumerTransaction(
                                guard.applyOnce(event, () -> effect("submission-7", 1))
                                        .then(Mono.error(new InjectedFailure()))))
                .expectError(InjectedFailure.class)
                .verify();
        assertThat(queryInt("SELECT count(*) FROM delivery.processed_event")).isZero();
        assertThat(queryInt("SELECT count(*) FROM delivery.outbox_consumer_effect")).isZero();

        StepVerifier.create(
                        consumerTransaction(
                                guard.applyOnce(event, () -> effect("submission-7", 1))))
                .expectNext(ProcessedEventOutcome.APPLIED)
                .verifyComplete();
        assertThat(queryInt("SELECT count(*) FROM delivery.processed_event")).isEqualTo(1);
        assertThat(queryInt("SELECT count(*) FROM delivery.outbox_consumer_effect")).isEqualTo(1);
    }

    @Test
    void consumerCrashAfterCommitAndDuplicateDeliveryDoNotRepeatTheEffect() throws Exception {
        ConsumedEvent event = consumedEvent(eventId(8));
        ProcessedEventGuard guard =
                new ProcessedEventGuard(
                        ConsumerModule.DELIVERY, databaseClient, mock(OutboxTelemetry.class));

        StepVerifier.create(
                        consumerTransaction(
                                guard.applyOnce(event, () -> effect("submission-8", 1))))
                .expectNext(ProcessedEventOutcome.APPLIED)
                .verifyComplete();
        StepVerifier.create(
                        consumerTransaction(
                                guard.applyOnce(event, () -> effect("submission-8", 1))))
                .expectNext(ProcessedEventOutcome.DELIVERY_DUPLICATE)
                .verifyComplete();

        assertThat(queryInt("SELECT count(*) FROM delivery.processed_event")).isEqualTo(1);
        assertThat(queryInt("SELECT count(*) FROM delivery.outbox_consumer_effect")).isEqualTo(1);
    }

    @Test
    void outOfOrderDeliveryRetainsTheNewestAggregateVersion() {
        ReferenceEventConsumer consumer = new ReferenceEventConsumer();

        StepVerifier.create(consumer.handle(envelope(eventId(9), 2)))
                .expectNext(ProcessedEventOutcome.APPLIED)
                .verifyComplete();
        StepVerifier.create(consumer.handle(envelope(eventId(10), 1)))
                .expectNext(ProcessedEventOutcome.STALE_VERSION)
                .verifyComplete();

        assertThat(consumer.revision("reference-1")).isEqualTo(2);
    }

    @Test
    void poisonPublicationFailsWithoutBlockingTheFollowingRow() throws Exception {
        StepVerifier.create(commitEvent(eventId(11)).then(commitEvent(eventId(12))))
                .verifyComplete();
        List<String> alerts = new ArrayList<>();
        RelayPublishStep poison =
                publishStep(
                        event -> Mono.error(new InjectedFailure()),
                        new DatabaseOutboxPublicationStore(databaseClient),
                        alerts);

        for (int attempt = 0; attempt < 8; attempt++) {
            executeAsClusterOwner(
                    "UPDATE outbox.outbox_event SET next_attempt_at = CURRENT_TIMESTAMP "
                            + "WHERE outbox_event_id = '"
                            + eventId(11)
                            + "'");
            ClaimedOutboxEvent claimed =
                    claim(1, "relay-poison", Instant.now().plusSeconds(30)).getFirst();
            StepVerifier.create(relayTransaction(poison.publishOne(claimed))).verifyComplete();
        }

        assertThat(
                        queryString(
                                "SELECT state FROM outbox.outbox_event WHERE outbox_event_id = '"
                                        + eventId(11)
                                        + "'"))
                .isEqualTo("FAILED");
        assertThat(alerts).containsExactly(eventId(11));

        ClaimedOutboxEvent following =
                claim(1, "relay-next", Instant.now().plusSeconds(30)).getFirst();
        RelayPublishStep healthy =
                publishStep(
                        event -> Mono.just(new OutboxBrokerPublisher.BrokerConfirmation(true)),
                        new DatabaseOutboxPublicationStore(databaseClient),
                        alerts);
        StepVerifier.create(relayTransaction(healthy.publishOne(following))).verifyComplete();
        assertThat(
                        queryString(
                                "SELECT state FROM outbox.outbox_event WHERE outbox_event_id = '"
                                        + eventId(12)
                                        + "'"))
                .isEqualTo("PUBLISHED");
    }

    private Mono<Void> commitEvent(String eventId) {
        return writeEvent(eventId, false);
    }

    private Mono<Void> writeEvent(String eventId, boolean injectFailure) {
        ReactiveOutboxWriter writer =
                new ReactiveOutboxWriter(
                        databaseClient,
                        new RegisteredEventSchemaValidator(
                                Path.of("contracts/events"), new ObjectMapper()),
                        new OutboxContextCarrier(),
                        mock(OutboxTelemetry.class));
        UUID probeId = UUID.fromString(eventId);
        Mono<Void> work =
                installModuleContext(TENANT_VALUE)
                        .then(
                                databaseClient
                                        .sql(
                                                """
                                                INSERT INTO delivery.outbox_atomicity_probe (
                                                    tenant_id, probe_id
                                                ) VALUES (:tenantId, :probeId)
                                                """)
                                        .bind("tenantId", TENANT_VALUE)
                                        .bind("probeId", probeId)
                                        .fetch()
                                        .rowsUpdated()
                                        .then())
                        .then(Mono.from(writer.append(TENANT, ACTOR, message(eventId))))
                        .then(injectFailure ? Mono.error(new InjectedFailure()) : Mono.empty());
        return transactions
                .transactional(work)
                .contextWrite(
                        context ->
                                context.put(ActorContext.class, ACTOR)
                                        .put(
                                                RequestContextPropagation.CORRELATION_ID_KEY,
                                                CORRELATION.toString()));
    }

    private List<ClaimedOutboxEvent> claim(int batchSize, String claimedBy, Instant expiresAt) {
        return Objects.requireNonNull(
                relayTransaction(
                                new OutboxClaimRepository(databaseClient)
                                        .claim(batchSize, claimedBy, expiresAt)
                                        .collectList())
                        .block());
    }

    private <T> Mono<T> relayTransaction(Mono<T> work) {
        Mono<T> secured =
                databaseClient
                        .sql("SET LOCAL ROLE app_outbox_relay")
                        .fetch()
                        .rowsUpdated()
                        .then(
                                databaseClient
                                        .sql(
                                                "SELECT set_config('app.platform_scope', "
                                                        + "'outbox_relay', true)")
                                        .fetch()
                                        .rowsUpdated())
                        .then(work);
        return transactions.transactional(secured);
    }

    private <T> Mono<T> consumerTransaction(Mono<T> work) {
        return transactions.transactional(installModuleContext(TENANT_VALUE).then(work));
    }

    private Mono<ProcessedEventOutcome> effect(String businessKey, int revision) {
        return databaseClient
                .sql(
                        """
                        INSERT INTO delivery.outbox_consumer_effect (
                            tenant_id, business_key, applied_revision
                        ) VALUES (:tenantId, :businessKey, :revision)
                        ON CONFLICT (tenant_id, business_key) DO NOTHING
                        """)
                .bind("tenantId", TENANT_VALUE)
                .bind("businessKey", businessKey)
                .bind("revision", revision)
                .fetch()
                .rowsUpdated()
                .map(
                        rows ->
                                rows == 1
                                        ? ProcessedEventOutcome.APPLIED
                                        : ProcessedEventOutcome.BUSINESS_DUPLICATE);
    }

    private static RelayPublishStep publishStep(
            OutboxBrokerPublisher publisher, OutboxPublicationStore store, List<String> alerts) {
        return new RelayPublishStep(
                publisher,
                store,
                (eventId, reason) -> Mono.fromRunnable(() -> alerts.add(eventId)),
                CLOCK,
                mock(OutboxTelemetry.class));
    }

    private static OutboxMessage message(String eventId) {
        return new OutboxMessage(
                OutboxEventId.parse(eventId),
                "platform.ReferenceEvent.v1",
                new AggregateReference("Reference", eventId),
                new ReferenceEvent("platform.ReferenceEvent.v1", eventId, 1),
                CORRELATION,
                Instant.parse("2026-09-03T12:00:00Z"));
    }

    private static ConsumedEvent consumedEvent(String eventId) {
        return new ConsumedEvent(
                eventId, TENANT_VALUE.toString(), EventType.parse("platform.ReferenceEvent.v1"));
    }

    private static ConsumerEnvelope envelope(String eventId, int revision) {
        return new ConsumerEnvelope(
                consumedEvent(eventId),
                "reference-1",
                "{\"eventType\":\"platform.ReferenceEvent.v1\","
                        + "\"referenceId\":\"reference-1\",\"revision\":"
                        + revision
                        + "}",
                CORRELATION.toString());
    }

    private static String eventId(int suffix) {
        return "01950f47-6000-7000-8000-%012d".formatted(suffix);
    }

    private static final class InjectedFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
