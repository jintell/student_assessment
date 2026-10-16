package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
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
import tools.jackson.databind.ObjectMapper;

class SliceTest {

    private static final TenantId TENANT = TenantId.parse("01950f47-6000-7000-8000-000000000001");
    private static final CorrelationId CORRELATION =
            CorrelationId.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    private static final ActorContext ACTOR =
            ActorContext.tenantWorkforce(
                    new ActorId("reference-slice-test"),
                    TENANT,
                    CORRELATION,
                    SourceIp.parse("127.0.0.1"));
    private static final String AGGREGATE_ID = "01950f47-6000-7000-8000-000000000003";

    private final InMemoryOutboxWriter writer = new InMemoryOutboxWriter();
    private final ReferenceEventConsumer consumer = new ReferenceEventConsumer();
    private final DeadLetterPublisher deadLetters = mock(DeadLetterPublisher.class);
    private final DeadLetterAlertSink alerts = mock(DeadLetterAlertSink.class);
    private final VersionAwareEventDispatcher dispatcher =
            new VersionAwareEventDispatcher(List.of(consumer), deadLetters, alerts);

    @Test
    void recordedEventReachesReferenceConsumerWithItsEnvelopeIntact() {
        record(1);
        ConsumerEnvelope envelope = envelope(0);

        StepVerifier.create(dispatcher.dispatch(envelope))
                .expectNext(VersionAwareEventDispatcher.DispatchResult.HANDLED)
                .verifyComplete();

        assertThat(envelope.event().tenantId()).isEqualTo(TENANT.toString());
        assertThat(envelope.correlationId()).isEqualTo(CORRELATION.toString());
        assertThat(consumer.revision(AGGREGATE_ID)).isEqualTo(1);
        verifyNoInteractions(deadLetters, alerts);
    }

    @Test
    void laterThenEarlierDeliveryPreservesLatestReferenceState() {
        record(2);
        record(1);

        StepVerifier.create(dispatcher.dispatch(envelope(0)).then(dispatcher.dispatch(envelope(1))))
                .expectNext(VersionAwareEventDispatcher.DispatchResult.HANDLED)
                .verifyComplete();

        assertThat(consumer.revision(AGGREGATE_ID)).isEqualTo(2);
        verifyNoInteractions(deadLetters, alerts);
    }

    @Test
    void unsupportedVersionIsDeadLetteredAndNeverApplied() {
        record(1);
        ConsumerEnvelope original = envelope(0);
        ConsumerEnvelope unsupported =
                new ConsumerEnvelope(
                        new ConsumedEvent(
                                original.event().eventId(),
                                original.event().tenantId(),
                                EventType.parse("platform.ReferenceEvent.v2")),
                        original.aggregateId(),
                        original.payload(),
                        original.correlationId());
        when(deadLetters.deadLetter(any(), any())).thenReturn(Mono.empty());
        when(alerts.deadLettered(anyString(), any())).thenReturn(Mono.empty());

        StepVerifier.create(dispatcher.dispatch(unsupported))
                .expectNext(
                        VersionAwareEventDispatcher.DispatchResult.DEAD_LETTERED_UNHANDLED_VERSION)
                .verifyComplete();

        assertThat(consumer.revision(AGGREGATE_ID)).isZero();
        verify(deadLetters)
                .deadLetter(
                        unsupported, DeadLetterPublisher.DeadLetterReason.UNHANDLED_EVENT_VERSION);
        verify(alerts)
                .deadLettered(
                        unsupported.event().eventId(),
                        DeadLetterPublisher.DeadLetterReason.UNHANDLED_EVENT_VERSION);
    }

    @Test
    void rejectedCorrelationDoesNotRecordOrApplyAnEvent() {
        ActorContext otherCorrelation =
                ActorContext.tenantWorkforce(
                        new ActorId("reference-slice-test"),
                        TENANT,
                        CorrelationId.parse("01ARZ3NDEKTSV4RRFFQ69G5FAW"),
                        SourceIp.parse("127.0.0.1"));

        StepVerifier.create(Mono.from(writer.append(TENANT, otherCorrelation, message(1))))
                .expectErrorMessage("Correlation context mismatch")
                .verify();

        assertThat(writer.events()).isEmpty();
        assertThat(consumer.revision(AGGREGATE_ID)).isZero();
        verifyNoInteractions(deadLetters, alerts);
    }

    private void record(int revision) {
        StepVerifier.create(Mono.from(writer.append(TENANT, ACTOR, message(revision))))
                .verifyComplete();
    }

    private static OutboxMessage message(int revision) {
        return new OutboxMessage(
                OutboxEventId.parse("01950f47-6000-7000-8000-%012d".formatted(revision)),
                "platform.ReferenceEvent.v1",
                new AggregateReference("Reference", AGGREGATE_ID),
                new ReferenceEvent("platform.ReferenceEvent.v1", AGGREGATE_ID, revision),
                CORRELATION,
                Instant.parse("2026-10-01T12:00:00Z"));
    }

    private ConsumerEnvelope envelope(int index) {
        InMemoryOutboxWriter.RecordedEvent recorded = writer.events().get(index);
        OutboxMessage message = recorded.message();
        return new ConsumerEnvelope(
                new ConsumedEvent(
                        message.eventId().toString(),
                        recorded.tenantId().toString(),
                        EventType.parse(message.eventType())),
                message.aggregate().aggregateId(),
                new ObjectMapper().writeValueAsString(message.payload()),
                message.correlationId().toString());
    }
}
