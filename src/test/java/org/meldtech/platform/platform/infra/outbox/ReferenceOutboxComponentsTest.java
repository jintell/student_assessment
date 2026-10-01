package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
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

class ReferenceOutboxComponentsTest {

    private static final TenantId TENANT = TenantId.parse("01950f47-6000-7000-8000-000000000001");
    private static final CorrelationId CORRELATION =
            CorrelationId.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");

    @Test
    void inMemoryWriterRecordsWithoutBrokerWiring() {
        InMemoryOutboxWriter writer = new InMemoryOutboxWriter();
        ActorContext actor =
                ActorContext.tenantWorkforce(
                        new ActorId("author-1"), TENANT, CORRELATION, SourceIp.parse("127.0.0.1"));
        OutboxMessage message =
                new OutboxMessage(
                        OutboxEventId.parse("01950f47-6000-7000-8000-000000000002"),
                        "platform.ReferenceEvent.v1",
                        new AggregateReference("Reference", "reference-1"),
                        new ReferenceEvent(
                                "platform.ReferenceEvent.v1",
                                "01950f47-6000-7000-8000-000000000003",
                                1),
                        CORRELATION,
                        Instant.parse("2026-09-03T12:00:00Z"));

        StepVerifier.create(Mono.from(writer.append(TENANT, actor, message))).verifyComplete();

        assertThat(writer.events())
                .singleElement()
                .extracting(event -> event.message().eventType())
                .isEqualTo("platform.ReferenceEvent.v1");
    }

    @Test
    void referenceConsumerIgnoresCausallyOlderEvent() {
        ReferenceEventConsumer consumer = new ReferenceEventConsumer();

        StepVerifier.create(consumer.handle(envelope(2)))
                .expectNext(ProcessedEventOutcome.APPLIED)
                .verifyComplete();
        StepVerifier.create(consumer.handle(envelope(1)))
                .expectNext(ProcessedEventOutcome.STALE_VERSION)
                .verifyComplete();
        assertThat(consumer.revision("reference-1")).isEqualTo(2);
    }

    private static ConsumerEnvelope envelope(int revision) {
        return new ConsumerEnvelope(
                new ConsumedEvent(
                        "01950f47-6000-7000-8000-000000000002",
                        TENANT.toString(),
                        EventType.parse("platform.ReferenceEvent.v1")),
                "reference-1",
                "{\"eventType\":\"platform.ReferenceEvent.v1\","
                        + "\"referenceId\":\"01950f47-6000-7000-8000-000000000003\","
                        + "\"revision\":"
                        + revision
                        + "}",
                CORRELATION.toString());
    }
}
