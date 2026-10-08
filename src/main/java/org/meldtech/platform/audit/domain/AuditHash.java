package org.meldtech.platform.audit.domain;

import java.util.Arrays;
import java.util.HexFormat;

public final class AuditHash {

    public static final int LENGTH = 32;

    private final short hashAlgorithmVersion;
    private final byte[] bytes;

    public AuditHash(short hashAlgorithmVersion, byte[] bytes) {
        if (hashAlgorithmVersion <= 0) {
            throw new IllegalArgumentException("hashAlgorithmVersion must be positive");
        }
        if (bytes.length != LENGTH) {
            throw new IllegalArgumentException("Audit hashes must contain exactly 32 bytes");
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

    public String hex() {
        return HexFormat.of().formatHex(bytes);
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate instanceof AuditHash other
                && hashAlgorithmVersion == other.hashAlgorithmVersion
                && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * Short.hashCode(hashAlgorithmVersion) + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "AuditHash[version=" + hashAlgorithmVersion + "]";
    }
}
