package org.meldtech.platform.audit.application;

import java.util.Objects;
import org.meldtech.platform.audit.domain.AuditRootHead;
import org.meldtech.platform.audit.domain.CanonicalJsonCodec;
import org.meldtech.platform.audit.domain.DerivedEpochRoot;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.audit.domain.EpochRootDerivation;
import org.meldtech.platform.audit.domain.EpochSealMaterial;
import org.meldtech.platform.audit.domain.SignedEpochSeal;
import org.meldtech.platform.audit.domain.UnsignedEpochSeal;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;

public final class AuditEpochSealer implements AuditEpochSealOperation {

    private final AuditEpochSealRepository repository;
    private final AuditEvidenceSigner signer;
    private final EpochRootDerivation rootDerivation;
    private final CanonicalJsonCodec codec;
    private final AuditSealTelemetry telemetry;
    private final int maxRetries;

    public AuditEpochSealer(
            AuditEpochSealRepository repository,
            AuditEvidenceSigner signer,
            EpochRootDerivation rootDerivation,
            CanonicalJsonCodec codec) {
        this(repository, signer, rootDerivation, codec, AuditSealTelemetry.NO_OP, 3);
    }

    public AuditEpochSealer(
            AuditEpochSealRepository repository,
            AuditEvidenceSigner signer,
            EpochRootDerivation rootDerivation,
            CanonicalJsonCodec codec,
            AuditSealTelemetry telemetry,
            int maxRetries) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.rootDerivation = Objects.requireNonNull(rootDerivation, "rootDerivation");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        this.maxRetries = maxRetries;
    }

    @Override
    public Mono<SealResult> seal(TenantId tenantId, EpochIdentity epoch) {
        return attempt(tenantId, epoch, 0);
    }

    public Mono<SealResult> sealOnce(TenantId tenantId, EpochIdentity epoch) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(epoch, "epoch");
        return repository
                .readRootHead(tenantId)
                .flatMap(
                        head -> prepare(tenantId, epoch, head).flatMap(seal -> commit(seal, head)));
    }

    private Mono<SealResult> attempt(TenantId tenantId, EpochIdentity epoch, int retries) {
        return sealOnce(tenantId, epoch)
                .flatMap(
                        result -> {
                            if (result == SealResult.SEALED || retries == maxRetries) {
                                return Mono.just(result);
                            }
                            telemetry.compareAndSwapRetry();
                            return attempt(tenantId, epoch, retries + 1);
                        });
    }

    private Mono<SignedEpochSeal> prepare(
            TenantId tenantId, EpochIdentity epoch, AuditRootHead observedRootHead) {
        return repository
                .loadEpochMaterial(tenantId, epoch, observedRootHead)
                .map(this::derive)
                .flatMap(
                        derived ->
                                repository
                                        .trustedSigningTime()
                                        .map(
                                                signedAt ->
                                                        new UnsignedEpochSeal(
                                                                derived.material(),
                                                                derived.root(),
                                                                signedAt)))
                .flatMap(
                        evidence ->
                                signer.sign(evidence.signingMessage(codec))
                                        .map(
                                                signature ->
                                                        new SignedEpochSeal(evidence, signature)));
    }

    private DerivedSeal derive(EpochSealMaterial material) {
        return new DerivedSeal(material, rootDerivation.derive(material));
    }

    private Mono<SealResult> commit(SignedEpochSeal seal, AuditRootHead observedRootHead) {
        return repository
                .insertSealAndCompareAndSwap(seal, observedRootHead)
                .map(committed -> committed ? SealResult.SEALED : SealResult.LOST_COMPARE_AND_SWAP);
    }

    private record DerivedSeal(EpochSealMaterial material, DerivedEpochRoot root) {}

    public enum SealResult {
        SEALED,
        LOST_COMPARE_AND_SWAP
    }
}
