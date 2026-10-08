package org.meldtech.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.audit.domain.AuditHash;
import org.meldtech.platform.audit.domain.AuditRootHead;
import org.meldtech.platform.audit.domain.AuditSignature;
import org.meldtech.platform.audit.domain.CanonicalJsonCodec;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.audit.domain.EpochRootDerivation;
import org.meldtech.platform.audit.domain.EpochSealMaterial;
import org.meldtech.platform.audit.domain.ShardSealMaterial;
import org.meldtech.platform.audit.domain.SignedEpochSeal;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuditEpochSealerTest {

    private static final Instant SIGNED_AT = Instant.parse("2026-09-03T12:00:00Z");
    private static final TenantId TENANT = TenantId.parse("01991a95-df27-7000-8000-000000000001");
    private static final EpochIdentity EPOCH =
            new EpochIdentity(RetentionClass.GENERAL_AUDIT_EVENT, YearMonth.of(2026, 8));

    @Test
    void derivesSignsAndAtomicallyAppendsTheSeal() {
        RecordingSealRepository repository = new RecordingSealRepository(true);
        AuditEpochSealer sealer = sealer(repository);

        StepVerifier.create(sealer.sealOnce(TENANT, EPOCH))
                .expectNext(AuditEpochSealer.SealResult.SEALED)
                .verifyComplete();

        SignedEpochSeal seal = Objects.requireNonNull(repository.committed.get());
        assertThat(seal.evidence().material().rootSequence()).isEqualTo(8);
        assertThat(seal.evidence().material().previousRootHash()).isEqualTo(repository.head.hash());
        assertThat(seal.evidence().derivedRoot().perShardCounts()).containsExactly(2L, 0L);
        assertThat(repository.observedAtCommit.get()).isSameAs(repository.head);
    }

    @Test
    void exposesALostCompareAndSwapWithoutMutatingShardMaterial() {
        RecordingSealRepository repository = new RecordingSealRepository(false);

        StepVerifier.create(sealer(repository).sealOnce(TENANT, EPOCH))
                .expectNext(AuditEpochSealer.SealResult.LOST_COMPARE_AND_SWAP)
                .verifyComplete();

        assertThat(repository.committed)
                .hasValueSatisfying(
                        seal ->
                                assertThat(seal.evidence().material().shards())
                                        .isEqualTo(repository.shards));
    }

    @Test
    void rereadsRederivesAndResignsAfterLosingTheCompareAndSwap() {
        RetryingSealRepository repository = new RetryingSealRepository();
        AtomicInteger retries = new AtomicInteger();
        AtomicInteger signatures = new AtomicInteger();
        CanonicalJsonCodec codec = new CanonicalJsonCodec();
        AuditEvidenceSigner signer =
                message -> {
                    signatures.incrementAndGet();
                    return Mono.just(
                            new AuditSignature(
                                    "key-v3",
                                    "RSASSA_PSS_SHA_256",
                                    new byte[] {7},
                                    "kms-request-" + signatures.get(),
                                    SIGNED_AT));
                };
        AuditEpochSealer sealer =
                new AuditEpochSealer(
                        repository,
                        signer,
                        new EpochRootDerivation(codec),
                        codec,
                        retries::incrementAndGet,
                        2);

        StepVerifier.create(sealer.seal(TENANT, EPOCH))
                .expectNext(AuditEpochSealer.SealResult.SEALED)
                .verifyComplete();

        assertThat(retries).hasValue(1);
        assertThat(signatures).hasValue(2);
        assertThat(repository.observedSequences).containsExactly(7L, 8L);
        assertThat(repository.roots.get(0)).isNotEqualTo(repository.roots.get(1));
        assertThat(repository.shards).containsExactlyElementsOf(repository.originalShards);
    }

    private static AuditEpochSealer sealer(RecordingSealRepository repository) {
        AuditEvidenceSigner signer =
                message ->
                        Mono.just(
                                new AuditSignature(
                                        "key-v3",
                                        "RSASSA_PSS_SHA_256",
                                        new byte[] {7},
                                        "kms-request",
                                        SIGNED_AT));
        CanonicalJsonCodec codec = new CanonicalJsonCodec();
        return new AuditEpochSealer(repository, signer, new EpochRootDerivation(codec), codec);
    }

    private static final class RecordingSealRepository implements AuditEpochSealRepository {

        private final AuditRootHead head = new AuditRootHead(7, hash(1));
        private final List<ShardSealMaterial> shards =
                List.of(ShardSealMaterial.populated(0, 2, hash(2)), ShardSealMaterial.empty(1));
        private final boolean commits;
        private final AtomicReference<SignedEpochSeal> committed = new AtomicReference<>();
        private final AtomicReference<AuditRootHead> observedAtCommit = new AtomicReference<>();

        private RecordingSealRepository(boolean commits) {
            this.commits = commits;
        }

        @Override
        public Mono<AuditRootHead> readRootHead(TenantId tenantId) {
            return Mono.just(head);
        }

        @Override
        public Mono<EpochSealMaterial> loadEpochMaterial(
                TenantId tenantId, EpochIdentity epoch, AuditRootHead observedRootHead) {
            return Mono.just(
                    new EpochSealMaterial(
                            tenantId,
                            epoch,
                            shards.size(),
                            (short) 1,
                            observedRootHead.hash(),
                            observedRootHead.nextSequence(),
                            shards));
        }

        @Override
        public Mono<Instant> trustedSigningTime() {
            return Mono.just(SIGNED_AT);
        }

        @Override
        public Mono<Boolean> insertSealAndCompareAndSwap(
                SignedEpochSeal seal, AuditRootHead observedRootHead) {
            committed.set(seal);
            observedAtCommit.set(observedRootHead);
            return Mono.just(commits);
        }
    }

    private static final class RetryingSealRepository implements AuditEpochSealRepository {

        private final AtomicInteger reads = new AtomicInteger();
        private final List<Long> observedSequences = new CopyOnWriteArrayList<>();
        private final List<AuditHash> roots = new CopyOnWriteArrayList<>();
        private final List<ShardSealMaterial> originalShards =
                List.of(ShardSealMaterial.populated(0, 2, hash(2)), ShardSealMaterial.empty(1));
        private final List<ShardSealMaterial> shards = new CopyOnWriteArrayList<>(originalShards);

        @Override
        public Mono<AuditRootHead> readRootHead(TenantId tenantId) {
            int read = reads.getAndIncrement();
            return Mono.just(new AuditRootHead(7L + read, hash(10 + read)));
        }

        @Override
        public Mono<EpochSealMaterial> loadEpochMaterial(
                TenantId tenantId, EpochIdentity epoch, AuditRootHead observedRootHead) {
            return Mono.just(
                    new EpochSealMaterial(
                            tenantId,
                            epoch,
                            shards.size(),
                            (short) 1,
                            observedRootHead.hash(),
                            observedRootHead.nextSequence(),
                            shards));
        }

        @Override
        public Mono<Instant> trustedSigningTime() {
            return Mono.just(SIGNED_AT);
        }

        @Override
        public Mono<Boolean> insertSealAndCompareAndSwap(
                SignedEpochSeal seal, AuditRootHead observedRootHead) {
            observedSequences.add(observedRootHead.sequence());
            roots.add(seal.evidence().derivedRoot().epochRoot());
            return Mono.just(observedSequences.size() > 1);
        }
    }

    private static AuditHash hash(int marker) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) marker;
        return new AuditHash((short) 1, bytes);
    }
}
