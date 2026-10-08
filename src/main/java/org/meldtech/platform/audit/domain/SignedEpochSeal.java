package org.meldtech.platform.audit.domain;

import java.util.Objects;

public record SignedEpochSeal(UnsignedEpochSeal evidence, AuditSignature signature) {

    public SignedEpochSeal {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(signature, "signature");
        if (!signature.signedAt().equals(evidence.signedAt())) {
            throw new IllegalArgumentException("signature time must match the signed seal time");
        }
    }
}
