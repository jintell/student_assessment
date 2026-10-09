package org.meldtech.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.audit.domain.AuditChainKey;
import org.meldtech.platform.audit.domain.AuditChainRecord;
import org.meldtech.platform.audit.domain.AuditHash;
import org.meldtech.platform.audit.domain.AuditHashing;
import org.meldtech.platform.audit.domain.AuditRootChainValidator;
import org.meldtech.platform.audit.domain.AuditRootHead;
import org.meldtech.platform.audit.domain.AuditSignature;
import org.meldtech.platform.audit.domain.AuditVerificationMismatch;
import org.meldtech.platform.audit.domain.CanonicalDocument;
import org.meldtech.platform.audit.domain.CanonicalJsonCodec;
import org.meldtech.platform.audit.domain.DerivedEpochRoot;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.audit.domain.EpochRootDerivation;
import org.meldtech.platform.audit.domain.EpochSealMaterial;
import org.meldtech.platform.audit.domain.ShardSealMaterial;
import org.meldtech.platform.audit.domain.SignedEpochSeal;
import org.meldtech.platform.audit.domain.UnsignedEpochSeal;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuditFullVerifierTest {

    private static final TenantId TENANT = TenantId.parse("01991a95-df27-7000-8000-000000000001");
    private static final Instant SIGNED_AT = Instant.parse("2026-09-03T12:00:00Z");
    private static final CanonicalJsonCodec CODEC = new CanonicalJsonCodec();
    private static final EpochRootDerivation ROOTS = new EpochRootDerivation(CODEC);

    @Test
    void quarterlyAndPostRestoreRunsRewalkRetainedEpochs() {
        CanonicalJsonCodec codec = new CanonicalJsonCodec();
        EpochRootDerivation derivation = new EpochRootDerivation(codec);
        FixtureEvidence evidence = new FixtureEvidence(codec, derivation);
        AuditFullVerifier verifier =
                new AuditFullVerifier(
                        evidence,
                        (message, signature) -> Mono.just(true),
                        new AuditRootChainValidator(derivation),
                        codec,
                        findingCapture(),
                        new AuditIntegrityFailureHandler(new RecordingPreservation()));

        StepVerifier.create(verifier.verify(AuditFullVerifier.Trigger.QUARTERLY))
                .assertNext(summary -> assertThat(summary.retainedChains()).isEqualTo(1))
                .verifyComplete();
        StepVerifier.create(
                        new AuditPostRestoreVerificationHook(verifier)
                                .verifyBeforeProductionWrites())
                .verifyComplete();

        assertThat(evidence.recordReads).hasValue(2);
    }

    @Test
    void brokenLinkRaisesP1HaltsAndPreservesEvidenceWithoutRepair() {
        EpochIdentity epoch = epoch(RetentionClass.GENERAL_AUDIT_EVENT, 2026, 8);
        AuditHash seed =
                AuditHashing.chainSeed(TENANT, epoch.retentionClass(), epoch.period(), 0, 1, CODEC);
        AuditHash wrongPredecessor = hash(41);
        CanonicalDocument event = document("broken-link");
        AuditChainRecord record =
                new AuditChainRecord(
                        1,
                        wrongPredecessor,
                        AuditHashing.recordHash(wrongPredecessor, event),
                        event);
        OpenAuditChain chain =
                new OpenAuditChain(
                        new AuditChainKey(TENANT, epoch, 0, 1),
                        seed,
                        1,
                        record.recordHash(),
                        List.of());

        assertFailure(ScenarioEvidence.forChain(chain, record), true, "predecessor does not match");
    }

    @Test
    void missingSequenceRaisesP1HaltsAndPreservesEvidenceWithoutRepair() {
        SignedEpochSeal seal =
                seal(2, zero(), epoch(RetentionClass.GENERAL_AUDIT_EVENT, 2026, 8), true);
        TenantRootEvidence roots =
                new TenantRootEvidence(
                        TENANT,
                        List.of(seal),
                        new AuditRootHead(2, seal.evidence().derivedRoot().epochRoot()));

        assertFailure(ScenarioEvidence.forRoots(roots), true, "sequence is not dense");
    }

    @Test
    void failedSignatureRaisesP1HaltsAndPreservesEvidenceWithoutRepair() {
        SignedEpochSeal seal =
                seal(1, zero(), epoch(RetentionClass.GENERAL_AUDIT_EVENT, 2026, 8), true);
        TenantRootEvidence roots =
                new TenantRootEvidence(
                        TENANT,
                        List.of(seal),
                        new AuditRootHead(1, seal.evidence().derivedRoot().epochRoot()));

        assertFailure(ScenarioEvidence.forRoots(roots), false, "signature is invalid");
    }

    @Test
    void nonReproducingRootRaisesP1HaltsAndPreservesEvidenceWithoutRepair() {
        SignedEpochSeal seal =
                seal(1, zero(), epoch(RetentionClass.GENERAL_AUDIT_EVENT, 2026, 8), false);
        TenantRootEvidence roots =
                new TenantRootEvidence(
                        TENANT,
                        List.of(seal),
                        new AuditRootHead(1, seal.evidence().derivedRoot().epochRoot()));

        assertFailure(ScenarioEvidence.forRoots(roots), true, "root does not reproduce");
    }

    @Test
    void siblingRootsAtOneSequenceRaiseP1HaltAndPreserveEvidenceWithoutRepair() {
        SignedEpochSeal first =
                seal(1, zero(), epoch(RetentionClass.RESULT_CORRECTION_EVIDENCE, 2026, 8), true);
        SignedEpochSeal sibling =
                seal(1, zero(), epoch(RetentionClass.RESULT_PUBLICATION_EVIDENCE, 2026, 8), true);
        TenantRootEvidence roots =
                new TenantRootEvidence(
                        TENANT,
                        List.of(first, sibling),
                        new AuditRootHead(1, first.evidence().derivedRoot().epochRoot()));

        assertFailure(ScenarioEvidence.forRoots(roots), true, "duplicate evidence");
    }

    private static void assertFailure(
            ScenarioEvidence evidence, boolean signaturesValid, String expectedMessage) {
        EvidenceSnapshot before = evidence.snapshot();
        RecordingPreservation preservation = new RecordingPreservation();
        AuditFullVerifier verifier =
                new AuditFullVerifier(
                        evidence,
                        (message, signature) -> Mono.just(signaturesValid),
                        new AuditRootChainValidator(ROOTS),
                        CODEC,
                        findingCapture(),
                        new AuditIntegrityFailureHandler(preservation));

        StepVerifier.create(verifier.verify(AuditFullVerifier.Trigger.QUARTERLY))
                .expectErrorSatisfies(
                        failure ->
                                assertThat(failure)
                                        .isInstanceOf(AuditVerificationMismatch.class)
                                        .hasMessageContaining(expectedMessage))
                .verify();

        assertThat(preservation.actions()).containsExactly("preserve", "metric", "p1", "halt");
        assertThat(preservation.findings())
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.tenantId()).isEqualTo(TENANT);
                            assertThat(finding.snapshotReference())
                                    .isEqualTo("snapshot-verification-1");
                            assertThat(finding.databaseLsn()).isEqualTo("0/16B6C50");
                            assertThat(finding.affectedIdentities()).isNotEmpty();
                        });
        assertThat(preservation.haltedTenants()).containsExactly(TENANT);
        assertThat(evidence.snapshot()).isEqualTo(before);
        assertThat(List.of(AuditEvidencePreservation.class.getMethods()))
                .extracting(java.lang.reflect.Method::getName)
                .noneMatch(name -> name.contains("repair") || name.contains("reconcile"));
    }

    private static AuditVerificationFindingCapture findingCapture() {
        return (tenantId, category, affectedIdentities) ->
                Mono.just(
                        new AuditVerificationFinding(
                                UUID.fromString("01991a95-df27-7000-8000-000000000099"),
                                tenantId,
                                category,
                                "snapshot-verification-1",
                                "0/16B6C50",
                                affectedIdentities,
                                SIGNED_AT));
    }

    private static SignedEpochSeal seal(
            long sequence, AuditHash predecessor, EpochIdentity epoch, boolean reproducible) {
        EpochSealMaterial material =
                new EpochSealMaterial(
                        TENANT,
                        epoch,
                        1,
                        (short) 1,
                        predecessor,
                        sequence,
                        List.of(ShardSealMaterial.empty(0)));
        DerivedEpochRoot reproduced = ROOTS.derive(material);
        DerivedEpochRoot recorded =
                reproducible
                        ? reproduced
                        : new DerivedEpochRoot(
                                hash(99), reproduced.perShardCounts(), reproduced.sequenceRanges());
        return new SignedEpochSeal(
                new UnsignedEpochSeal(material, recorded, SIGNED_AT),
                new AuditSignature(
                        "key-v3",
                        "RSASSA_PSS_SHA_256",
                        new byte[] {1},
                        "kms-request-" + sequence + "-" + epoch.retentionClass(),
                        SIGNED_AT));
    }

    private static EpochIdentity epoch(RetentionClass retentionClass, int year, int month) {
        return new EpochIdentity(retentionClass, YearMonth.of(year, month));
    }

    private static CanonicalDocument document(String event) {
        return CODEC.encode(new ObjectValue(Map.of("event", new StringValue(event))));
    }

    private static AuditHash zero() {
        return new AuditHash((short) 1, new byte[32]);
    }

    private static AuditHash hash(int marker) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) marker;
        return new AuditHash((short) 1, bytes);
    }

    private static final class FixtureEvidence implements AuditFullVerificationEvidence {

        private final OpenAuditChain chain;
        private final AuditChainRecord record;
        private final TenantRootEvidence roots;
        private final AtomicInteger recordReads = new AtomicInteger();

        private FixtureEvidence(CanonicalJsonCodec codec, EpochRootDerivation derivation) {
            EpochIdentity epoch =
                    new EpochIdentity(RetentionClass.GENERAL_AUDIT_EVENT, YearMonth.of(2026, 8));
            AuditChainKey key = new AuditChainKey(TENANT, epoch, 0, 1);
            AuditHash seed =
                    AuditHashing.chainSeed(
                            TENANT, epoch.retentionClass(), epoch.period(), 0, 1, codec);
            CanonicalDocument document =
                    codec.encode(new ObjectValue(Map.of("event", new StringValue("retained"))));
            AuditHash head = AuditHashing.recordHash(seed, document);
            record = new AuditChainRecord(1, seed, head, document);
            chain = new OpenAuditChain(key, seed, 1, head, List.of());
            AuditHash zero = new AuditHash((short) 1, new byte[32]);
            EpochSealMaterial material =
                    new EpochSealMaterial(
                            TENANT,
                            epoch,
                            1,
                            (short) 1,
                            zero,
                            1,
                            List.of(ShardSealMaterial.populated(0, 1, head)));
            DerivedEpochRoot derived = derivation.derive(material);
            SignedEpochSeal seal =
                    new SignedEpochSeal(
                            new UnsignedEpochSeal(material, derived, SIGNED_AT),
                            new AuditSignature(
                                    "key-v3",
                                    "RSASSA_PSS_SHA_256",
                                    new byte[] {1},
                                    "kms-request",
                                    SIGNED_AT));
            roots =
                    new TenantRootEvidence(
                            TENANT, List.of(seal), new AuditRootHead(1, derived.epochRoot()));
        }

        @Override
        public Flux<OpenAuditChain> retainedChains() {
            return Flux.just(chain);
        }

        @Override
        public Flux<AuditChainRecord> records(OpenAuditChain ignored) {
            recordReads.incrementAndGet();
            return Flux.just(record);
        }

        @Override
        public Flux<TenantRootEvidence> tenantRootChains() {
            return Flux.just(roots);
        }
    }

    private static final class ScenarioEvidence implements AuditFullVerificationEvidence {

        private final Map<OpenAuditChain, List<AuditChainRecord>> chains;
        private final List<TenantRootEvidence> roots;

        private ScenarioEvidence(
                Map<OpenAuditChain, List<AuditChainRecord>> chains,
                List<TenantRootEvidence> roots) {
            this.chains = Map.copyOf(chains);
            this.roots = List.copyOf(roots);
        }

        private static ScenarioEvidence forChain(OpenAuditChain chain, AuditChainRecord record) {
            return new ScenarioEvidence(Map.of(chain, List.of(record)), List.of());
        }

        private static ScenarioEvidence forRoots(TenantRootEvidence roots) {
            return new ScenarioEvidence(Map.of(), List.of(roots));
        }

        @Override
        public Flux<OpenAuditChain> retainedChains() {
            return Flux.fromIterable(chains.keySet());
        }

        @Override
        public Flux<AuditChainRecord> records(OpenAuditChain chain) {
            return Flux.fromIterable(chains.getOrDefault(chain, List.of()));
        }

        @Override
        public Flux<TenantRootEvidence> tenantRootChains() {
            return Flux.fromIterable(roots);
        }

        private EvidenceSnapshot snapshot() {
            return new EvidenceSnapshot(chains, roots);
        }
    }

    private record EvidenceSnapshot(
            Map<OpenAuditChain, List<AuditChainRecord>> chains, List<TenantRootEvidence> roots) {

        private EvidenceSnapshot {
            chains = Map.copyOf(chains);
            roots = List.copyOf(roots);
        }
    }

    private static final class RecordingPreservation implements AuditEvidencePreservation {

        private final List<String> actions = new CopyOnWriteArrayList<>();
        private final List<AuditVerificationFinding> findings = new CopyOnWriteArrayList<>();
        private final List<TenantId> haltedTenants = new CopyOnWriteArrayList<>();

        @Override
        public Mono<Void> preserve(AuditVerificationFinding finding) {
            findings.add(finding);
            actions.add("preserve");
            return Mono.empty();
        }

        @Override
        public Mono<Void> recordHighSeverityMetric(AuditVerificationFinding finding) {
            actions.add("metric");
            return Mono.empty();
        }

        @Override
        public Mono<Void> raiseP1Alert(AuditVerificationFinding finding) {
            actions.add("p1");
            return Mono.empty();
        }

        @Override
        public Mono<Void> haltSealingAndDisposition(TenantId tenantId) {
            haltedTenants.add(tenantId);
            actions.add("halt");
            return Mono.empty();
        }

        private List<String> actions() {
            return List.copyOf(actions);
        }

        private List<AuditVerificationFinding> findings() {
            return List.copyOf(findings);
        }

        private List<TenantId> haltedTenants() {
            return List.copyOf(haltedTenants);
        }
    }
}
