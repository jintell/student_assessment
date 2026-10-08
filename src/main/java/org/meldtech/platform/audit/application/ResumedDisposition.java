package org.meldtech.platform.audit.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ResumedDisposition(
        UUID requestId, Instant originalRetentionStart, Instant originalDueAt) {

    public ResumedDisposition {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(originalRetentionStart, "originalRetentionStart");
        Objects.requireNonNull(originalDueAt, "originalDueAt");
    }
}
