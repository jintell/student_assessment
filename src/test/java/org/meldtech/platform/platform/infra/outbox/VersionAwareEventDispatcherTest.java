package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class VersionAwareEventDispatcherTest {

    @Test
    void unhandledVersionIsDeadLetteredWithDistinctReason() {
        List<DeadLetterPublisher.DeadLetterReason> reasons = new ArrayList<>();
        VersionAwareEventDispatcher dispatcher =
                new VersionAwareEventDispatcher(
                        List.of(),
                        (envelope, reason) -> Mono.fromRunnable(() -> reasons.add(reason)));

        StepVerifier.create(dispatcher.dispatch(envelope(2, 2)))
                .expectNext(
                        VersionAwareEventDispatcher.DispatchResult.DEAD_LETTERED_UNHANDLED_VERSION)
                .verifyComplete();

        assertThat(reasons)
                .containsExactly(DeadLetterPublisher.DeadLetterReason.UNHANDLED_EVENT_VERSION);
    }

    @Test
    void dispatcherSupportsVersionGuardedOutOfOrderDelivery() {
        AtomicInteger revision = new AtomicInteger();
        EventConsumer consumer =
                new EventConsumer() {
                    @Override
                    public Set<EventType> handledVersions() {
                        return Set.of(EventType.parse("platform.ReferenceEvent.v1"));
                    }

                    @Override
                    public Mono<ProcessedEventOutcome> handle(ConsumerEnvelope envelope) {
                        int candidate = Integer.parseInt(envelope.payload());
                        return Mono.just(
                                revision.accumulateAndGet(candidate, Math::max) == candidate
                                        ? ProcessedEventOutcome.APPLIED
                                        : ProcessedEventOutcome.STALE_VERSION);
                    }
                };
        VersionAwareEventDispatcher dispatcher =
                new VersionAwareEventDispatcher(
                        List.of(consumer), (envelope, reason) -> Mono.empty());

        StepVerifier.create(
                        dispatcher
                                .dispatch(envelope(1, 2))
                                .then(dispatcher.dispatch(envelope(1, 1))))
                .expectNext(VersionAwareEventDispatcher.DispatchResult.HANDLED)
                .verifyComplete();
        assertThat(revision).hasValue(2);
    }

    private static ConsumerEnvelope envelope(int eventVersion, int aggregateRevision) {
        EventType type = EventType.parse("platform.ReferenceEvent.v" + eventVersion);
        return new ConsumerEnvelope(
                new ConsumedEvent(
                        "01950f47-6000-7000-8000-000000000001",
                        "01950f47-6000-7000-8000-000000000002",
                        type),
                "reference-1",
                Integer.toString(aggregateRevision),
                "01ARZ3NDEKTSV4RRFFQ69G5FAV");
    }
}
