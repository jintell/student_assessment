package org.meldtech.platform.audit.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

public final class AuditSignature {

    private final String keyVersion;
    private final String algorithm;
    private final byte[] signature;
    private final String providerRequestId;
    private final Instant signedAt;

    public AuditSignature(
            String keyVersion,
            String algorithm,
            byte[] signature,
            String providerRequestId,
            Instant signedAt) {
        this.keyVersion = requireText(keyVersion, "keyVersion");
        this.algorithm = requireText(algorithm, "algorithm");
        Objects.requireNonNull(signature, "signature");
        if (signature.length == 0) {
            throw new IllegalArgumentException("signature must not be empty");
        }
        this.signature = Arrays.copyOf(signature, signature.length);
        this.providerRequestId = requireText(providerRequestId, "providerRequestId");
        this.signedAt = Objects.requireNonNull(signedAt, "signedAt");
    }

    public String keyVersion() {
        return keyVersion;
    }

    public String algorithm() {
        return algorithm;
    }

    public byte[] signature() {
        return Arrays.copyOf(signature, signature.length);
    }

    public String providerRequestId() {
        return providerRequestId;
    }

    public Instant signedAt() {
        return signedAt;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
