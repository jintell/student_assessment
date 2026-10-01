package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RelayPublishStepTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    @Test
    void nackRetriesWithoutPublishingAndTerminalFailureAlerts() {
        RecordingStore store = new RecordingStore(true);
        List<String> alerts = new ArrayList<>();
        RelayPublishStep step =
                new RelayPublishStep(
                        event -> Mono.just(new OutboxBrokerPublisher.BrokerConfirmation(false)),
                        store,
                        (eventId, reason) -> Mono.fromRunnable(() -> alerts.add(eventId)),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        new RecordingTelemetry());

        StepVerifier.create(step.publishOne(event(7))).verifyComplete();

        assertThat(store.published).isEmpty();
        assertThat(store.failures).containsExactly(PublicationFailureReason.BROKER_NACK);
        assertThat(alerts).containsExactly("01950f47-6000-7000-8000-000000000001");
    }

    @Test
    void publishesBatchSequentially() {
        List<String> calls = new ArrayList<>();
        RecordingStore store = new RecordingStore(false);
        RelayPublishStep step =
                new RelayPublishStep(
                        event -> {
                            calls.add(event.eventId());
                            return Mono.just(new OutboxBrokerPublisher.BrokerConfirmation(true));
                        },
                        store,
                        (eventId, reason) -> Mono.empty(),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        new RecordingTelemetry());

        StepVerifier.create(step.publishSequentially(Flux.just(event(0), event(0))))
                .verifyComplete();

        assertThat(calls).hasSize(2);
        assertThat(store.published).hasSize(2);
    }

    private static ClaimedOutboxEvent event(int attempts) {
        return new ClaimedOutboxEvent(
                "01950f47-6000-7000-8000-000000000001",
                "01950f47-6000-7000-8000-000000000002",
                "Assessment",
                "assessment-1",
                EventType.parse("platform.ReferenceEvent.v1"),
                "{}",
                "01ARZ3NDEKTSV4RRFFQ69G5FAV",
                Optional.empty(),
                Optional.empty(),
                attempts,
                "relay-a",
                NOW.minusSeconds(2),
                NOW.minusSeconds(1));
    }

    private static final class RecordingStore implements OutboxPublicationStore {

        private final boolean terminal;
        private final List<String> published = new ArrayList<>();
        private final List<PublicationFailureReason> failures = new ArrayList<>();

        private RecordingStore(boolean terminal) {
            this.terminal = terminal;
        }

        @Override
        public Mono<Void> markPublished(ClaimedOutboxEvent event, Instant publishedAt) {
            return Mono.fromRunnable(() -> published.add(event.eventId()));
        }

        @Override
        public Mono<FailureTransition> recordFailure(
                ClaimedOutboxEvent event, PublicationFailureReason reason, Instant nextAttemptAt) {
            failures.add(reason);
            return Mono.just(new FailureTransition(terminal, event.attemptCount() + 1));
        }
    }

    private static final class RecordingTelemetry implements OutboxTelemetry {

        @Override
        public void writerAppended() {}

        @Override
        public void relayPublished() {}

        @Override
        public void relayPublishFailed(PublicationFailureReason reason) {}

        @Override
        public void staleClaimsReclaimed(long count) {}

        @Override
        public void eventFailed() {}

        @Override
        public void consumerDuplicate() {}

        @Override
        public void relayTick(java.time.Duration duration) {}
    }
}
