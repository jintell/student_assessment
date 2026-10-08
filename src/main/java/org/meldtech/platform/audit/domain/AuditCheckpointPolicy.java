package org.meldtech.platform.audit.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class AuditCheckpointPolicy {

    public static final long MAX_UNCHECKPOINTED_RECORDS = 10_000;
    public static final Duration MAX_UNCHECKPOINTED_AGE = Duration.ofHours(1);

    public boolean shouldCheckpoint(
            AuditCheckpointTail tail, Instant observedAt, boolean epochBoundary) {
        Objects.requireNonNull(tail, "tail");
        Objects.requireNonNull(observedAt, "observedAt");
        if (tail.recordCount() == 0) {
            return false;
        }
        return epochBoundary
                || tail.recordCount() >= MAX_UNCHECKPOINTED_RECORDS
                || !tail.oldestUncheckpointedAt()
                        .orElseThrow()
                        .isAfter(observedAt.minus(MAX_UNCHECKPOINTED_AGE));
    }
}
