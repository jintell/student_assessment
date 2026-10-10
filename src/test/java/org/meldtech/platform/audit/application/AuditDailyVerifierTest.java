package org.meldtech.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.meldtech.platform.audit.domain.AuditChainKey;
import org.meldtech.platform.audit.domain.AuditChainRecord;
import org.meldtech.platform.audit.domain.AuditHash;
import org.meldtech.platform.audit.domain.AuditHashing;
import org.meldtech.platform.audit.domain.AuditRootHead;
import org.meldtech.platform.audit.domain.AuditSignature;
import org.meldtech.platform.audit.domain.AuditSigningMessage;
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
import reactor.test.StepVerifier;

class AuditDailyVerifierTest {

    private static final Instant SIGNED_AT = Instant.parse("2026-09-03T12:00:00Z");
    private static final TenantId TENANT = TenantId.parse("01991a95-df27-7000-8000-000000000001");
    private static final CanonicalJsonCodec CODEC = new CanonicalJsonCodec();

    @Test
    void walksOpenChainsButChecksSealedEpochsFromAnchorsOnly() {
        Fixture fixture = fixture();
        AuditDailyVerifier verifier =
                new AuditDailyVerifier(
                        fixture.evidence,
                        (message, signature) -> reactor.core.publisher.Mono.just(true),
                        fixture.rootDerivation,
                        CODEC);

        StepVerifier.create(verifier.verify())
                .assertNext(
                        summary -> {
                            assertThat(summary.openChains()).isEqualTo(1);
                            assertThat(summary.sealedEpochs()).isEqualTo(1);
                        })
                .verifyComplete();

        assertThat(fixture.evidence.recordReads).isEqualTo(1);
    }

    private static Fixture fixture() {
        return fixture(0);
    }

    @Test
    void retainedRecordGrowthDoesNotIncreaseDailyWalkCost(TestReporter reporter) {
        List<Integer> recordReads = new ArrayList<>();
        List<Integer> signatureChecks = new ArrayList<>();
        for (long retainedRecords : new long[] {10, 1_000_000}) {
            Fixture fixture = fixture(retainedRecords);
            List<AuditSigningMessage> checked = new ArrayList<>();
            AuditDailyVerifier verifier =
                    new AuditDailyVerifier(
                            fixture.evidence,
                            (message, signature) -> {
                                checked.add(message);
                                assertThat(signature).isSameAs(fixture.evidence.seal.signature());
                                return reactor.core.publisher.Mono.just(true);
                            },
                            fixture.rootDerivation,
                            CODEC);

            StepVerifier.create(verifier.verify())
                    .expectNext(new AuditDailyVerifier.VerificationSummary(1, 1))
                    .verifyComplete();

            assertThat(checked)
                    .extracting(message -> java.util.HexFormat.of().formatHex(message.bytes()))
                    .containsExactlyInAnyOrder(
                            java.util.HexFormat.of()
                                    .formatHex(
                                            fixture.evidence
                                                    .open
                                                    .checkpoints()
                                                    .getFirst()
                                                    .signingMessage()
                                                    .bytes()),
                            java.util.HexFormat.of()
                                    .formatHex(
                                            fixture.evidence
                                                    .seal
                                                    .evidence()
                                                    .signingMessage(CODEC)
                                                    .bytes()));
            assertThat(fixture.evidence.rowsRead).isEqualTo(1);
            recordReads.add(fixture.evidence.recordReads);
            signatureChecks.add(checked.size());
            reporter.publishEntry(
                    Map.of(
                            "retainedRecords", Long.toString(retainedRecords),
                            "openRecordQueries", Integer.toString(fixture.evidence.recordReads),
                            "openRecordsRead", Integer.toString(fixture.evidence.rowsRead),
                            "signatureChecks", Integer.toString(checked.size())));
        }
        assertThat(recordReads).containsExactly(1, 1);
        assertThat(signatureChecks).containsExactly(2, 2);
    }

    private static Fixture fixture(long retainedRecords) {
        EpochIdentity openEpoch =
                new EpochIdentity(RetentionClass.GENERAL_AUDIT_EVENT, YearMonth.of(2026, 9));
        AuditChainKey key = new AuditChainKey(TENANT, openEpoch, 0, 1);
        AuditHash seed =
                AuditHashing.chainSeed(
                        TENANT, openEpoch.retentionClass(), openEpoch.period(), 0, 1, CODEC);
        CanonicalDocument event =
                CODEC.encode(new ObjectValue(java.util.Map.of("event", new StringValue("saved"))));
        AuditHash head = AuditHashing.recordHash(seed, event);
        AuditChainRecord record = new AuditChainRecord(1, seed, head, event);
        AuditSigningMessage checkpointMessage =
                AuditSigningMessage.checkpoint("checkpoint-1", (short) 1, new byte[] {1});
        AuditSignature signature = signature();
        OpenAuditChain open =
                new OpenAuditChain(
                        key,
                        seed,
                        1,
                        head,
                        List.of(new AuditCheckpointAnchor(1, head, checkpointMessage, signature)));

        EpochIdentity sealedEpoch =
                new EpochIdentity(RetentionClass.GENERAL_AUDIT_EVENT, YearMonth.of(2026, 8));
        EpochRootDerivation derivation = new EpochRootDerivation(CODEC);
        EpochSealMaterial material =
                new EpochSealMaterial(
                        TENANT,
                        sealedEpoch,
                        1,
                        (short) 1,
                        new AuditRootHead(0, hash(0)).hash(),
                        1,
                        List.of(
                                retainedRecords == 0
                                        ? ShardSealMaterial.empty(0)
                                        : ShardSealMaterial.populated(
                                                0, retainedRecords, hash(42))));
        DerivedEpochRoot derived = derivation.derive(material);
        SignedEpochSeal seal =
                new SignedEpochSeal(new UnsignedEpochSeal(material, derived, SIGNED_AT), signature);
        RecordingEvidence evidence = new RecordingEvidence(open, record, seal);
        return new Fixture(evidence, derivation);
    }

    private static AuditSignature signature() {
        return new AuditSignature(
                "key-v3", "RSASSA_PSS_SHA_256", new byte[] {7}, "kms-request", SIGNED_AT);
    }

    private static AuditHash hash(int marker) {
        byte[] value = new byte[32];
        value[0] = (byte) marker;
        return new AuditHash((short) 1, value);
    }

    private record Fixture(RecordingEvidence evidence, EpochRootDerivation rootDerivation) {}

    private static final class RecordingEvidence implements AuditVerificationEvidence {

        private final OpenAuditChain open;
        private final AuditChainRecord record;
        private final SignedEpochSeal seal;
        private int recordReads;
        private int rowsRead;

        private RecordingEvidence(
                OpenAuditChain open, AuditChainRecord record, SignedEpochSeal seal) {
            this.open = open;
            this.record = record;
            this.seal = seal;
        }

        @Override
        public Flux<OpenAuditChain> openChains() {
            return Flux.just(open);
        }

        @Override
        public Flux<AuditChainRecord> records(OpenAuditChain chain) {
            assertThat(chain).as("only the open chain may be read").isEqualTo(open);
            recordReads++;
            return Flux.just(record).doOnNext(ignored -> rowsRead++);
        }

        @Override
        public Flux<SignedEpochSeal> sealedEpochs() {
            return Flux.just(seal);
        }
    }
}
