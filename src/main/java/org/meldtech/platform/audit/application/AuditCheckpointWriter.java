package org.meldtech.platform.audit.application;

import java.time.Instant;
import java.util.Objects;
import org.meldtech.platform.audit.domain.AuditChainKey;
import org.meldtech.platform.audit.domain.AuditCheckpoint;
import org.meldtech.platform.audit.domain.AuditCheckpointPolicy;
import org.meldtech.platform.audit.domain.AuditCheckpointTail;
import org.meldtech.platform.audit.domain.CanonicalJsonCodec;
import org.meldtech.platform.audit.domain.UnsignedAuditCheckpoint;
import reactor.core.publisher.Mono;

public final class AuditCheckpointWriter {

    private final AuditCheckpointRepository repository;
    private final AuditEvidenceSigner signer;
    private final AuditCheckpointPolicy policy;
    private final CanonicalJsonCodec codec;

    public AuditCheckpointWriter(
            AuditCheckpointRepository repository,
            AuditEvidenceSigner signer,
            AuditCheckpointPolicy policy,
            CanonicalJsonCodec codec) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public Mono<WriteResult> writeIfDue(
            AuditChainKey chainKey, Instant observedAt, boolean epochBoundary) {
        Objects.requireNonNull(chainKey, "chainKey");
        Objects.requireNonNull(observedAt, "observedAt");
        return repository
                .loadVerifiedTail(chainKey)
                .flatMap(tail -> writeIfDue(tail, observedAt, epochBoundary));
    }

    private Mono<WriteResult> writeIfDue(
            AuditCheckpointTail tail, Instant observedAt, boolean epochBoundary) {
        if (!policy.shouldCheckpoint(tail, observedAt, epochBoundary)) {
            return Mono.just(WriteResult.NOT_DUE);
        }
        return repository
                .trustedSigningTime()
                .map(signedAt -> new UnsignedAuditCheckpoint(tail, signedAt))
                .flatMap(
                        evidence ->
                                signer.sign(evidence.signingMessage(codec))
                                        .map(signature -> new AuditCheckpoint(evidence, signature)))
                .flatMap(repository::insert)
                .map(inserted -> inserted ? WriteResult.WRITTEN : WriteResult.ALREADY_WRITTEN);
    }

    public enum WriteResult {
        NOT_DUE,
        WRITTEN,
        ALREADY_WRITTEN
    }
}
