package org.meldtech.platform.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.audit.domain.AuditSigningMessage;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class KmsAuditSignerTest {

    private static final KmsKeyMetadata VALID_KEY =
            new KmsKeyMetadata(true, true, true, true, "test", KmsAuditSigner.SIGNATURE_ALGORITHM);

    @Test
    void signsOnlyADigestThroughTheNonExportableSealerKey() {
        AtomicReference<byte[]> capturedDigest = new AtomicReference<>();
        KmsSigningClient client = client(capturedDigest);
        KmsAuditSigner signer =
                new KmsAuditSigner(
                        "kms://test/audit-signing", "test", client, (kind, id) -> Mono.just(false));
        AuditSigningMessage message =
                AuditSigningMessage.checkpoint(
                        "tenant-1/general/2026-10/7/10000",
                        (short) 1,
                        "canonical-checkpoint".getBytes(StandardCharsets.UTF_8));

        StepVerifier.create(signer.sign(message))
                .assertNext(
                        signature -> {
                            assertThat(signature.keyVersion()).isEqualTo("version-7");
                            assertThat(signature.algorithm())
                                    .isEqualTo(KmsAuditSigner.SIGNATURE_ALGORITHM);
                        })
                .verifyComplete();

        assertThat(capturedDigest.get()).hasSize(32).isNotEqualTo(message.bytes());
    }

    @Test
    void refusesToResignAnExistingCheckpointOrSeal() {
        KmsAuditSigner signer =
                new KmsAuditSigner(
                        "kms://test/audit-signing",
                        "test",
                        client(new AtomicReference<>()),
                        (kind, id) -> Mono.just(true));
        AuditSigningMessage seal =
                AuditSigningMessage.epochSeal(
                        "tenant-1/general/2026-10",
                        (short) 1,
                        "canonical-seal".getBytes(StandardCharsets.UTF_8));

        StepVerifier.create(signer.sign(seal))
                .expectErrorMessage("Existing audit evidence cannot be re-signed")
                .verify();
    }

    @Test
    void rejectsAKeyOutsideTheDedicatedWorkloadBoundary() {
        KmsSigningClient client =
                new KmsSigningClient() {
                    @Override
                    public Mono<KmsKeyMetadata> describeKey(String keyReference) {
                        return Mono.just(
                                new KmsKeyMetadata(
                                        true,
                                        true,
                                        false,
                                        true,
                                        "test",
                                        KmsAuditSigner.SIGNATURE_ALGORITHM));
                    }

                    @Override
                    public Mono<KmsSignResponse> signDigest(
                            String keyReference, String algorithm, byte[] sha256Digest) {
                        return Mono.error(new AssertionError("sign must not be called"));
                    }
                };
        KmsAuditSigner signer =
                new KmsAuditSigner(
                        "kms://test/audit-signing", "test", client, (kind, id) -> Mono.just(false));

        StepVerifier.create(signer.verifyReady())
                .expectErrorMessage("Audit signing key does not satisfy the sealer boundary")
                .verify();
    }

    private static KmsSigningClient client(AtomicReference<byte[]> capturedDigest) {
        return new KmsSigningClient() {
            @Override
            public Mono<KmsKeyMetadata> describeKey(String keyReference) {
                return Mono.just(VALID_KEY);
            }

            @Override
            public Mono<KmsSignResponse> signDigest(
                    String keyReference, String algorithm, byte[] sha256Digest) {
                capturedDigest.set(sha256Digest.clone());
                return Mono.just(
                        new KmsSignResponse(
                                "version-7",
                                algorithm,
                                new byte[] {1, 2, 3},
                                "request-1",
                                Instant.parse("2026-10-08T00:00:01Z")));
            }
        };
    }
}
