package org.meldtech.platform.audit.infra;

import java.time.Instant;
import java.util.Arrays;

final class KmsSignResponse {

    private final String keyVersion;
    private final String algorithm;
    private final byte[] signature;
    private final String requestId;
    private final Instant signedAt;

    KmsSignResponse(
            String keyVersion,
            String algorithm,
            byte[] signature,
            String requestId,
            Instant signedAt) {
        this.keyVersion = keyVersion;
        this.algorithm = algorithm;
        this.signature = Arrays.copyOf(signature, signature.length);
        this.requestId = requestId;
        this.signedAt = signedAt;
    }

    String keyVersion() {
        return keyVersion;
    }

    String algorithm() {
        return algorithm;
    }

    byte[] signature() {
        return Arrays.copyOf(signature, signature.length);
    }

    String requestId() {
        return requestId;
    }

    Instant signedAt() {
        return signedAt;
    }
}
