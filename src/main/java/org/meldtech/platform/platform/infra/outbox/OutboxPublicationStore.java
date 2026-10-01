package org.meldtech.platform.platform.infra.outbox;

import java.time.Duration;
import java.time.Instant;
import reactor.core.publisher.Mono;

interface OutboxPublicationStore {

    Mono<Void> markPublished(ClaimedOutboxEvent event, Instant publishedAt);

    Mono<FailureTransition> recordFailure(
            ClaimedOutboxEvent event, PublicationFailureReason reason, Instant nextAttemptAt);

    record FailureTransition(boolean terminal, int attemptCount) {}

    static Duration retryDelay(ClaimedOutboxEvent event) {
        int exponent = Math.min(event.attemptCount(), 7);
        long baseMillis = Math.min(30_000L, 200L << exponent);
        long jitterRange = Math.max(1L, baseMillis / 4L);
        long jitter = Math.floorMod(event.eventId().hashCode(), jitterRange);
        return Duration.ofMillis(Math.min(30_000L, baseMillis + jitter));
    }
}
