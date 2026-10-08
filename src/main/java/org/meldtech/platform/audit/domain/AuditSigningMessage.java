package org.meldtech.platform.audit.domain;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

public final class AuditSigningMessage {

    private static final byte[] CHECKPOINT_DOMAIN =
            "meldtech.audit.checkpoint-signature.v1\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EPOCH_SEAL_DOMAIN =
            "meldtech.audit.epoch-seal-signature.v1\0".getBytes(StandardCharsets.UTF_8);

    private final AuditEvidenceKind kind;
    private final String evidenceId;
    private final short hashAlgorithmVersion;
    private final byte[] bytes;

    private AuditSigningMessage(
            AuditEvidenceKind kind,
            String evidenceId,
            short hashAlgorithmVersion,
            byte[] canonicalEvidence,
            byte[] domain) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.evidenceId = Objects.requireNonNull(evidenceId, "evidenceId");
        if (evidenceId.isBlank()) {
            throw new IllegalArgumentException("evidenceId must not be blank");
        }
        if (hashAlgorithmVersion <= 0) {
            throw new IllegalArgumentException("hashAlgorithmVersion must be positive");
        }
        this.hashAlgorithmVersion = hashAlgorithmVersion;
        this.bytes = new byte[domain.length + canonicalEvidence.length];
        System.arraycopy(domain, 0, bytes, 0, domain.length);
        System.arraycopy(canonicalEvidence, 0, bytes, domain.length, canonicalEvidence.length);
    }

    public static AuditSigningMessage checkpoint(
            String evidenceId, short hashAlgorithmVersion, byte[] canonicalEvidence) {
        return new AuditSigningMessage(
                AuditEvidenceKind.CHECKPOINT,
                evidenceId,
                hashAlgorithmVersion,
                canonicalEvidence,
                CHECKPOINT_DOMAIN);
    }

    public static AuditSigningMessage epochSeal(
            String evidenceId, short hashAlgorithmVersion, byte[] canonicalEvidence) {
        return new AuditSigningMessage(
                AuditEvidenceKind.EPOCH_SEAL,
                evidenceId,
                hashAlgorithmVersion,
                canonicalEvidence,
                EPOCH_SEAL_DOMAIN);
    }

    public AuditEvidenceKind kind() {
        return kind;
    }

    public String evidenceId() {
        return evidenceId;
    }

    public short hashAlgorithmVersion() {
        return hashAlgorithmVersion;
    }

    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
