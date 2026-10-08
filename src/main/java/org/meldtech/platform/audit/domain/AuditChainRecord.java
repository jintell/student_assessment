package org.meldtech.platform.audit.domain;

import java.util.Objects;

public record AuditChainRecord(
        long sequence,
        AuditHash previousHash,
        AuditHash recordHash,
        CanonicalDocument canonicalEvent) {

    public AuditChainRecord {
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Objects.requireNonNull(previousHash, "previousHash");
        Objects.requireNonNull(recordHash, "recordHash");
        Objects.requireNonNull(canonicalEvent, "canonicalEvent");
    }
}
