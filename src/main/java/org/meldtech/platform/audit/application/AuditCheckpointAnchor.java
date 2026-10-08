package org.meldtech.platform.audit.application;

import java.util.Objects;
import org.meldtech.platform.audit.domain.AuditHash;
import org.meldtech.platform.audit.domain.AuditSignature;
import org.meldtech.platform.audit.domain.AuditSigningMessage;

public record AuditCheckpointAnchor(
        long sequenceEnd,
        AuditHash headHash,
        AuditSigningMessage signingMessage,
        AuditSignature signature) {

    public AuditCheckpointAnchor {
        if (sequenceEnd <= 0) {
            throw new IllegalArgumentException("sequenceEnd must be positive");
        }
        Objects.requireNonNull(headHash, "headHash");
        Objects.requireNonNull(signingMessage, "signingMessage");
        Objects.requireNonNull(signature, "signature");
    }
}
