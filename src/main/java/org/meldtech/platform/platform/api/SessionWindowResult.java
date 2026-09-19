package org.meldtech.platform.platform.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record SessionWindowResult(
        SessionWindowState state,
        SessionWindowReason reason,
        String source,
        Instant observedAt,
        Optional<Instant> nextBoundary) {

    public SessionWindowResult {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(nextBoundary, "nextBoundary");
        if (Objects.requireNonNull(source, "source").isBlank()) {
            throw new IllegalArgumentException("Session-window source must not be blank");
        }
    }
}
