package org.meldtech.platform.platform.infra.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

record OutboxBacklogSnapshot(
        long pending,
        long claimed,
        long published,
        long failed,
        Duration oldestPendingAge,
        Instant observedAt) {

    OutboxBacklogSnapshot {
        Objects.requireNonNull(oldestPendingAge, "oldestPendingAge");
        Objects.requireNonNull(observedAt, "observedAt");
        if (pending < 0
                || claimed < 0
                || published < 0
                || failed < 0
                || oldestPendingAge.isNegative()
                || (pending == 0 && !oldestPendingAge.isZero())) {
            throw new IllegalArgumentException("Invalid outbox backlog snapshot");
        }
    }
}
