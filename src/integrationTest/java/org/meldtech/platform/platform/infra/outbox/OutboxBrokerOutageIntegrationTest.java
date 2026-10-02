package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.RabbitMqTestContainer;
import org.meldtech.platform.shared.infra.web.RequestContextPropagation;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.OutboxEventId;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.meldtech.platform.shared.kernel.outbox.AggregateReference;
import org.meldtech.platform.shared.kernel.outbox.OutboxMessage;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class OutboxBrokerOutageIntegrationTest extends OutboxPostgreSqlIntegrationTestSupport {

    private static final int EVENT_COUNT = 20;
    private static final String TEST_QUEUE = "integration.platform-outage-test";
    private static final UUID TENANT_VALUE =
            UUID.fromString("01950f47-6000-7000-8000-000000000001");
    private static final TenantId TENANT = TenantId.parse(TENANT_VALUE.toString());
    private static final CorrelationId CORRELATION =
            CorrelationId.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    private static final ActorContext ACTOR =
            ActorContext.tenantWorkforce(
                    new ActorId("outbox-outage-test"),
                    TENANT,
                    CORRELATION,
                    SourceIp.parse("127.0.0.1"));
    private static final Instant OUTAGE_STARTED = Instant.parse("2026-10-01T12:00:00Z");

    @Test
    void tenMinuteBrokerOutageDelaysButDoesNotLoseOrDuplicatePropagation() throws Exception {
        RabbitMQContainer rabbitMq = RabbitMqTestContainer.newInstance();
        rabbitMq.start();
        rabbitMq.stop();

        try {
            for (int index = 0; index < EVENT_COUNT; index++) {
                Instant occurredAt = OUTAGE_STARTED.plusSeconds((600L * index) / (EVENT_COUNT - 1));
                StepVerifier.create(commitEvent(index + 1, occurredAt)).verifyComplete();
            }

            assertThat(queryInt("SELECT count(*) FROM delivery.outbox_atomicity_probe"))
                    .as("request-path writes committed while RabbitMQ was unavailable")
                    .isEqualTo(EVENT_COUNT);
            assertThat(
                            queryInt(
                                    "SELECT count(*) FROM outbox.outbox_event "
                                            + "WHERE state = 'PENDING'"))
                    .as("durable backlog accumulated over the ten-minute logical window")
                    .isEqualTo(EVENT_COUNT);

            rabbitMq.start();
            CachingConnectionFactory connectionFactory = connectionFactory(rabbitMq);
            try {
                RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
                declareTestTopology(rabbitTemplate);

                List<ClaimedOutboxEvent> claimed = claimAll();
                assertThat(claimed)
                        .extracting(ClaimedOutboxEvent::eventId)
                        .containsExactly(expectedEventIds().toArray(String[]::new));

                RelayPublishStep relay =
                        new RelayPublishStep(
                                new RabbitOutboxBrokerPublisher(rabbitTemplate),
                                new DatabaseOutboxPublicationStore(databaseClient),
                                (eventId, reason) -> Mono.empty(),
                                Clock.fixed(OUTAGE_STARTED.plusSeconds(601), ZoneOffset.UTC),
                                mock(OutboxTelemetry.class));
                StepVerifier.create(
                                relay.publishSequentially(
                                        reactor.core.publisher.Flux.fromIterable(claimed)))
                        .verifyComplete();

                List<String> delivered = receiveMessageIds(rabbitTemplate);
                assertThat(delivered).containsExactlyElementsOf(expectedEventIds());
                assertThat(delivered).doesNotHaveDuplicates();
                applyConsumerEffects(delivered);
            } finally {
                connectionFactory.destroy();
            }

            assertThat(
                            queryInt(
                                    "SELECT count(*) FROM outbox.outbox_event "
                                            + "WHERE state = 'PUBLISHED'"))
                    .isEqualTo(EVENT_COUNT);
            assertThat(queryInt("SELECT count(*) FROM delivery.processed_event"))
                    .isEqualTo(EVENT_COUNT);
            assertThat(queryInt("SELECT count(*) FROM delivery.outbox_consumer_effect"))
                    .isEqualTo(EVENT_COUNT);
        } finally {
            rabbitMq.stop();
        }
    }

    private Mono<Void> commitEvent(int index, Instant occurredAt) {
        String eventId = eventId(index);
        ReactiveOutboxWriter writer =
                new ReactiveOutboxWriter(
                        databaseClient,
                        new RegisteredEventSchemaValidator(
                                Path.of("contracts/events"), new ObjectMapper()),
                        new OutboxContextCarrier(),
                        mock(OutboxTelemetry.class));
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
                                        .bind("probeId", UUID.fromString(eventId))
                                        .fetch()
                                        .rowsUpdated()
                                        .then())
                        .then(
                                Mono.from(
                                        writer.append(
                                                TENANT,
                                                ACTOR,
                                                new OutboxMessage(
                                                        OutboxEventId.parse(eventId),
                                                        "platform.ReferenceEvent.v1",
                                                        new AggregateReference(
                                                                "Reference", eventId),
                                                        new ReferenceEvent(
                                                                "platform.ReferenceEvent.v1",
                                                                eventId,
                                                                index),
                                                        CORRELATION,
                                                        occurredAt))));
        return transactions
                .transactional(work)
                .contextWrite(
                        context ->
                                context.put(ActorContext.class, ACTOR)
                                        .put(
                                                RequestContextPropagation.CORRELATION_ID_KEY,
                                                CORRELATION.toString()));
    }

    private List<ClaimedOutboxEvent> claimAll() {
        Mono<List<ClaimedOutboxEvent>> work =
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
                        .thenMany(
                                new OutboxClaimRepository(databaseClient)
                                        .claim(
                                                EVENT_COUNT,
                                                "outage-recovery-relay",
                                                OUTAGE_STARTED.plusSeconds(630)))
                        .collectList();
        return Objects.requireNonNull(transactions.transactional(work).block());
    }

    private void applyConsumerEffects(List<String> delivered) {
        ProcessedEventGuard guard =
                new ProcessedEventGuard(
                        ConsumerModule.DELIVERY, databaseClient, mock(OutboxTelemetry.class));
        for (String eventId : delivered) {
            ConsumedEvent event =
                    new ConsumedEvent(
                            eventId,
                            TENANT_VALUE.toString(),
                            EventType.parse("platform.ReferenceEvent.v1"));
            Mono<ProcessedEventOutcome> apply =
                    installModuleContext(TENANT_VALUE)
                            .then(
                                    guard.applyOnce(
                                            event,
                                            () ->
                                                    databaseClient
                                                            .sql(
                                                                    """
                                                                    INSERT INTO delivery.outbox_consumer_effect (
                                                                        tenant_id, business_key,
                                                                        applied_revision
                                                                    ) VALUES (
                                                                        :tenantId, :businessKey, 1
                                                                    )
                                                                    ON CONFLICT (
                                                                        tenant_id, business_key
                                                                    ) DO NOTHING
                                                                    """)
                                                            .bind("tenantId", TENANT_VALUE)
                                                            .bind("businessKey", eventId)
                                                            .fetch()
                                                            .rowsUpdated()
                                                            .map(
                                                                    rows ->
                                                                            rows == 1
                                                                                    ? ProcessedEventOutcome
                                                                                            .APPLIED
                                                                                    : ProcessedEventOutcome
                                                                                            .BUSINESS_DUPLICATE)));
            StepVerifier.create(transactions.transactional(apply))
                    .expectNext(ProcessedEventOutcome.APPLIED)
                    .verifyComplete();
        }
    }

    private static CachingConnectionFactory connectionFactory(RabbitMQContainer rabbitMq) {
        CachingConnectionFactory connectionFactory =
                new CachingConnectionFactory(rabbitMq.getHost(), rabbitMq.getAmqpPort());
        connectionFactory.setUsername(rabbitMq.getAdminUsername());
        connectionFactory.setPassword(rabbitMq.getAdminPassword());
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        return connectionFactory;
    }

    private static void declareTestTopology(RabbitTemplate rabbitTemplate) {
        RabbitAdmin admin = new RabbitAdmin(rabbitTemplate);
        TopicExchange exchange =
                new TopicExchange(
                        IntegrationTopologyConfiguration.INTEGRATION_EXCHANGE, true, false);
        Queue queue = QueueBuilder.durable(TEST_QUEUE).quorum().build();
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("platform.#"));
    }

    private static List<String> receiveMessageIds(RabbitTemplate rabbitTemplate) {
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < EVENT_COUNT; index++) {
            var message = rabbitTemplate.receive(TEST_QUEUE, 5_000);
            assertThat(message).as("outage recovery message %s", index + 1).isNotNull();
            ids.add(Objects.requireNonNull(message).getMessageProperties().getMessageId());
            assertThat(new String(message.getBody(), StandardCharsets.UTF_8))
                    .contains("platform.ReferenceEvent.v1");
        }
        return ids;
    }

    private static List<String> expectedEventIds() {
        List<String> ids = new ArrayList<>();
        for (int index = 1; index <= EVENT_COUNT; index++) {
            ids.add(eventId(index));
        }
        return ids;
    }

    private static String eventId(int suffix) {
        return "01950f47-6000-7001-8000-%012d".formatted(suffix);
    }
}
