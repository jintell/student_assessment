package org.meldtech.platform.audit.infra;

import java.util.Objects;
import org.meldtech.platform.audit.application.AuditEvidenceSigner;
import org.meldtech.platform.audit.domain.AuditHashing;
import org.meldtech.platform.audit.domain.AuditSignature;
import org.meldtech.platform.audit.domain.AuditSigningMessage;
import reactor.core.publisher.Mono;

final class KmsAuditSigner implements AuditEvidenceSigner {

    static final String SIGNATURE_ALGORITHM = "RSASSA_PSS_SHA_256";

    private final String keyReference;
    private final String environment;
    private final KmsSigningClient client;
    private final SignedAuditEvidenceLookup signedEvidence;

    KmsAuditSigner(
            String keyReference,
            String environment,
            KmsSigningClient client,
            SignedAuditEvidenceLookup signedEvidence) {
        this.keyReference = requireText(keyReference, "keyReference");
        this.environment = requireText(environment, "environment");
        this.client = Objects.requireNonNull(client, "client");
        this.signedEvidence = Objects.requireNonNull(signedEvidence, "signedEvidence");
    }

    Mono<Void> verifyReady() {
        return client.describeKey(keyReference).flatMap(this::validateKey);
    }

    @Override
    public Mono<AuditSignature> sign(AuditSigningMessage message) {
        Objects.requireNonNull(message, "message");
        return signedEvidence
                .isAlreadySigned(message.kind(), message.evidenceId())
                .flatMap(
                        alreadySigned ->
                                alreadySigned
                                        ? Mono.error(
                                                new IllegalStateException(
                                                        "Existing audit evidence cannot be re-signed"))
                                        : verifyReady()
                                                .then(
                                                        client.signDigest(
                                                                keyReference,
                                                                SIGNATURE_ALGORITHM,
                                                                AuditHashing.sha256()
                                                                        .digest(message.bytes()))))
                .map(this::toSignature);
    }

    private Mono<Void> validateKey(KmsKeyMetadata metadata) {
        if (!metadata.enabled()
                || !metadata.asymmetric()
                || !metadata.nonExportable()
                || !metadata.auditSealerSignOnly()
                || !environment.equals(metadata.environment())
                || !SIGNATURE_ALGORITHM.equals(metadata.algorithm())) {
            return Mono.error(
                    new IllegalStateException(
                            "Audit signing key does not satisfy the sealer boundary"));
        }
        return Mono.empty();
    }

    private AuditSignature toSignature(KmsSignResponse response) {
        if (!SIGNATURE_ALGORITHM.equals(response.algorithm())) {
            throw new IllegalStateException("KMS returned an unexpected signature algorithm");
        }
        return new AuditSignature(
                response.keyVersion(),
                response.algorithm(),
                response.signature(),
                response.requestId(),
                response.signedAt());
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
