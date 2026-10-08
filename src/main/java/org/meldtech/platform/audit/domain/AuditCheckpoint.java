package org.meldtech.platform.audit.domain;

import java.util.Objects;

public record AuditCheckpoint(UnsignedAuditCheckpoint evidence, AuditSignature signature) {

    public AuditCheckpoint {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(signature, "signature");
        if (!signature.signedAt().equals(evidence.signedAt())) {
            throw new IllegalArgumentException(
                    "signature time must match the signed checkpoint time");
        }
    }
}
