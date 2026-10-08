package org.meldtech.platform.audit.domain;

import java.util.Arrays;

public final class CanonicalDocument {

    private final short hashAlgorithmVersion;
    private final byte[] bytes;

    public CanonicalDocument(short hashAlgorithmVersion, byte[] bytes) {
        if (hashAlgorithmVersion <= 0) {
            throw new IllegalArgumentException("hashAlgorithmVersion must be positive");
        }
        this.hashAlgorithmVersion = hashAlgorithmVersion;
        this.bytes = Arrays.copyOf(bytes, bytes.length);
    }

    public short hashAlgorithmVersion() {
        return hashAlgorithmVersion;
    }

    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
