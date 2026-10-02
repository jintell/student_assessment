package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class OutboxAtomicityIntegrationTest extends OutboxPostgreSqlIntegrationTestSupport {

    private static final UUID TENANT_VALUE =
            UUID.fromString("01950f47-6000-7000-8000-000000000001");
    private static final TenantId TENANT = TenantId.parse(TENANT_VALUE.toString());
    private static final CorrelationId CORRELATION =
            CorrelationId.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    private static final ActorContext ACTOR =
            ActorContext.tenantWorkforce(
                    new ActorId("outbox-atomicity-test"),
                    TENANT,
                    CORRELATION,
                    SourceIp.parse("127.0.0.1"));

    @Test
    void outboxRowCommitsAndRollsBackWithItsBusinessTransaction() throws Exception {
        ReactiveOutboxWriter writer =
                new ReactiveOutboxWriter(
                        databaseClient,
                        new RegisteredEventSchemaValidator(
                                Path.of("contracts/events"), new ObjectMapper()),
                        new OutboxContextCarrier(),
                        mock(OutboxTelemetry.class));

        StepVerifier.create(businessWrite(writer, "01950f47-6000-7000-8000-000000000011", false))
                .verifyComplete();

        assertThat(queryInt("SELECT count(*) FROM delivery.outbox_atomicity_probe"))
                .as("committed business rows")
                .isEqualTo(1);
        assertThat(queryInt("SELECT count(*) FROM outbox.outbox_event"))
                .as("outbox rows committed with the business write")
                .isEqualTo(1);

        StepVerifier.create(businessWrite(writer, "01950f47-6000-7000-8000-000000000012", true))
                .expectErrorMessage("injected business failure")
                .verify();

        assertThat(queryInt("SELECT count(*) FROM delivery.outbox_atomicity_probe"))
                .as("the failed business write was rolled back")
                .isEqualTo(1);
        assertThat(queryInt("SELECT count(*) FROM outbox.outbox_event"))
                .as("the failed business write left no outbox row")
                .isEqualTo(1);
    }

    private Mono<Void> businessWrite(
            ReactiveOutboxWriter writer, String eventId, boolean injectFailure) {
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
                        .then(
                                injectFailure
                                        ? Mono.error(
                                                new IllegalStateException(
                                                        "injected business failure"))
                                        : Mono.empty());
        return transactions
                .transactional(work)
                .contextWrite(
                        context ->
                                context.put(ActorContext.class, ACTOR)
                                        .put(
                                                RequestContextPropagation.CORRELATION_ID_KEY,
                                                CORRELATION.toString()));
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
}
