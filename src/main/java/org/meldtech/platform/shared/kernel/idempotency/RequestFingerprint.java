package org.meldtech.platform.shared.kernel.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

public final class RequestFingerprint {

    private static final int SHA_256_BYTES = 32;
    private final byte[] digest;

    private RequestFingerprint(byte[] digest) {
        if (digest.length != SHA_256_BYTES) {
            throw new IllegalArgumentException("Request fingerprint must be a SHA-256 digest");
        }
        this.digest = digest.clone();
    }

    public static RequestFingerprint sha256(String canonicalRequest) {
        Objects.requireNonNull(canonicalRequest, "canonicalRequest");
        try {
            MessageDigest algorithm = MessageDigest.getInstance("SHA-256");
            return new RequestFingerprint(
                    algorithm.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK does not provide SHA-256", impossible);
        }
    }

    public static RequestFingerprint parseHex(String value) {
        Objects.requireNonNull(value, "value");
        try {
            return new RequestFingerprint(HexFormat.of().parseHex(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Request fingerprint must be hexadecimal SHA-256", exception);
        }
    }

    public String hex() {
        return HexFormat.of().formatHex(digest);
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate instanceof RequestFingerprint other
                && MessageDigest.isEqual(digest, other.digest);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(digest);
    }

    @Override
    public String toString() {
        return hex();
    }
}
