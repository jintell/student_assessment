package org.meldtech.platform.audit.domain;

import java.util.Objects;

public record AuditRootHead(long sequence, AuditHash hash) {

    public AuditRootHead {
        if (sequence < 0) {
            throw new IllegalArgumentException("root sequence must not be negative");
        }
        Objects.requireNonNull(hash, "hash");
    }

    public long nextSequence() {
        return Math.addExact(sequence, 1);
    }
}
