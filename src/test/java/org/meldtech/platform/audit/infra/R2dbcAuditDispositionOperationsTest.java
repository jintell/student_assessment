package org.meldtech.platform.audit.infra;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.audit.application.AuditDispositionEligibility;
import org.meldtech.platform.audit.application.AuditDispositionEvidenceEmitter;
import org.meldtech.platform.audit.application.AuditDispositionExecutor;
import org.meldtech.platform.audit.application.AuditFullVerifier;
import org.meldtech.platform.audit.application.AuditSignatureVerifier;
import org.meldtech.platform.audit.application.DispositionRequest;
import org.meldtech.platform.audit.application.OpenAuditChain;
import org.meldtech.platform.audit.application.TenantRootEvidence;
import org.meldtech.platform.audit.domain.AuditChainKey;
import org.meldtech.platform.audit.domain.AuditHash;
import org.meldtech.platform.audit.domain.AuditRootChainValidator;
import org.meldtech.platform.audit.domain.AuditRootHead;
import org.meldtech.platform.audit.domain.AuditSignature;
import org.meldtech.platform.audit.domain.CanonicalJsonCodec;
import org.meldtech.platform.audit.domain.DerivedEpochRoot;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.audit.domain.EpochSealMaterial;
import org.meldtech.platform.audit.domain.ShardSealMaterial;
import org.meldtech.platform.audit.domain.ShardSequenceRange;
import org.meldtech.platform.audit.domain.SignedEpochSeal;
import org.meldtech.platform.audit.domain.UnsignedEpochSeal;
import org.meldtech.platform.platform.api.TransactionalCollaboration;
import org.meldtech.platform.platform.api.TransactionalConnection;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class R2dbcAuditDispositionOperationsTest {

    private static final TenantId TENANT = TenantId.parse("00000000-0000-0000-0000-000000000111");
    private static final EpochIdentity EPOCH =
            new EpochIdentity(RetentionClass.GENERAL_AUDIT_EVENT, YearMonth.of(2025, 7));
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
    private static final DispositionRequest REQUEST =
            new DispositionRequest(
                    UUID.fromString("00000000-0000-0000-0000-000000000112"),
                    TENANT,
                    EPOCH,
                    "general",
                    2,
                    Instant.parse("2025-07-01T00:00:00Z"),
                    Instant.parse("2026-07-01T00:00:00Z"),
                    "approval-1");

    @Test
    void executesEveryDispositionOperationAndRecordsCompletion() {
        ScriptedR2dbc database =
                new ScriptedR2dbc(
                        sql -> {
                            if (sql.contains("evidence_count")) {
                                return ScriptedR2dbc.QueryResult.row(Map.of("evidence_count", 1L));
                            }
                            if (sql.contains("audit_disposition_lifecycle")) {
                                return ScriptedR2dbc.QueryResult.updated(1);
                            }
                            if (sql.startsWith("ALTER TABLE")) {
                                return ScriptedR2dbc.QueryResult.updated(0);
                            }
                            throw new AssertionError("Unexpected SQL: " + sql);
                        });
        SignedEpochSeal seal = seal();
        R2dbcAuditFullVerificationEvidence evidence =
                mock(R2dbcAuditFullVerificationEvidence.class);
        OpenAuditChain emptyChain = emptyChain();
        when(evidence.retainedChains(EPOCH)).thenReturn(Flux.just(emptyChain));
        when(evidence.records(emptyChain)).thenReturn(Flux.empty());
        when(evidence.rootEvidence())
                .thenReturn(
                        Mono.just(
                                new TenantRootEvidence(
                                        TENANT,
                                        List.of(seal),
                                        new AuditRootHead(
                                                1, seal.evidence().derivedRoot().epochRoot()))));
        when(evidence.seal(EPOCH)).thenReturn(Mono.just(seal));
        AuditSignatureVerifier signatures = mock(AuditSignatureVerifier.class);
        when(signatures.verify(any(), any())).thenReturn(Mono.just(true));
        AuditFullVerifier fullVerifier = mock(AuditFullVerifier.class);
        when(fullVerifier.verify(AuditFullVerifier.Trigger.QUARTERLY))
                .thenReturn(
                        Mono.just(
                                new AuditFullVerifier.FullVerificationSummary(
                                        AuditFullVerifier.Trigger.QUARTERLY, 1, 1)));
        AuditDispositionEvidenceEmitter emitter = mock(AuditDispositionEvidenceEmitter.class);
        when(emitter.emit(any(), any(), any(), any())).thenReturn(Mono.empty());
        AuditDispositionEligibility eligibility = request -> Mono.just(true);
        R2dbcAuditDispositionOperations operations =
                new R2dbcAuditDispositionOperations(
                        database.connectionFactory(),
                        evidence,
                        signatures,
                        mock(AuditRootChainValidator.class),
                        new CanonicalJsonCodec(),
                        fullVerifier,
                        emitter,
                        directTransactions(),
                        eligibility,
                        actor(),
                        Clock.fixed(NOW, ZoneOffset.UTC));

        StepVerifier.create(operations.freezeAndVerifyShardEvidence(REQUEST)).verifyComplete();
        StepVerifier.create(operations.verifySealInDenseRootChain(REQUEST)).verifyComplete();
        StepVerifier.create(operations.emitAndVerifyDispositionEvidence(REQUEST)).verifyComplete();
        StepVerifier.create(operations.recheckPolicyAndDisposePartition(REQUEST)).verifyComplete();
        StepVerifier.create(operations.verifyRetainedEvidence(REQUEST)).verifyComplete();
        StepVerifier.create(
                        operations.recordProgress(
                                REQUEST, AuditDispositionExecutor.Step.RETAINED_EVIDENCE_VERIFIED))
                .verifyComplete();
    }

    @Test
    void failsClosedForAbsentEvidenceInvalidSignaturePolicyChangeAndProgressConflict() {
        ScriptedR2dbc database =
                new ScriptedR2dbc(
                        sql ->
                                sql.contains("audit_disposition_lifecycle")
                                        ? ScriptedR2dbc.QueryResult.updated(0)
                                        : ScriptedR2dbc.QueryResult.empty());
        R2dbcAuditFullVerificationEvidence evidence =
                mock(R2dbcAuditFullVerificationEvidence.class);
        when(evidence.retainedChains(EPOCH)).thenReturn(Flux.empty());
        SignedEpochSeal seal = seal();
        when(evidence.rootEvidence())
                .thenReturn(
                        Mono.just(
                                new TenantRootEvidence(
                                        TENANT,
                                        List.of(seal),
                                        new AuditRootHead(
                                                1, seal.evidence().derivedRoot().epochRoot()))));
        when(evidence.seal(EPOCH)).thenReturn(Mono.just(seal));
        AuditSignatureVerifier signatures = mock(AuditSignatureVerifier.class);
        when(signatures.verify(any(), any())).thenReturn(Mono.just(false));
        R2dbcAuditDispositionOperations operations =
                operations(database, evidence, signatures, request -> Mono.just(false));

        StepVerifier.create(operations.freezeAndVerifyShardEvidence(REQUEST))
                .expectErrorMessage("epoch has no evidence")
                .verify();
        StepVerifier.create(operations.verifySealInDenseRootChain(REQUEST))
                .expectErrorMessage("audit epoch seal signature is invalid")
                .verify();
        StepVerifier.create(operations.recheckPolicyAndDisposePartition(REQUEST))
                .expectErrorMessage("audit epoch disposition is no longer eligible")
                .verify();
        StepVerifier.create(
                        operations.recordProgress(
                                REQUEST, AuditDispositionExecutor.Step.SHARD_EVIDENCE_VERIFIED))
                .expectErrorMessage("disposition progress identity changed")
                .verify();
    }

    private static R2dbcAuditDispositionOperations operations(
            ScriptedR2dbc database,
            R2dbcAuditFullVerificationEvidence evidence,
            AuditSignatureVerifier signatures,
            AuditDispositionEligibility eligibility) {
        AuditFullVerifier verifier = mock(AuditFullVerifier.class);
        AuditDispositionEvidenceEmitter emitter = mock(AuditDispositionEvidenceEmitter.class);
        return new R2dbcAuditDispositionOperations(
                database.connectionFactory(),
                evidence,
                signatures,
                mock(AuditRootChainValidator.class),
                new CanonicalJsonCodec(),
                verifier,
                emitter,
                directTransactions(),
                eligibility,
                actor(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static TransactionalCollaboration directTransactions() {
        return new TransactionalCollaboration() {
            @Override
            public <T> Mono<T> inExamEntryTransaction(
                    TenantId tenantId, Function<TransactionalConnection, Mono<T>> work) {
                return work.apply(sql -> mock(io.r2dbc.spi.Statement.class));
            }
        };
    }

    private static OpenAuditChain emptyChain() {
        AuditHash seed = hash(4);
        return new OpenAuditChain(new AuditChainKey(TENANT, EPOCH, 0, 1), seed, 0, seed, List.of());
    }

    private static SignedEpochSeal seal() {
        AuditHash previous = hash(1);
        AuditHash head = hash(2);
        AuditHash root = hash(3);
        EpochSealMaterial material =
                new EpochSealMaterial(
                        TENANT,
                        EPOCH,
                        1,
                        (short) 1,
                        previous,
                        1,
                        List.of(ShardSealMaterial.populated(0, 1, head)));
        DerivedEpochRoot derived =
                new DerivedEpochRoot(
                        root,
                        List.of(1L),
                        List.of(new ShardSequenceRange(0, OptionalLong.of(1), OptionalLong.of(1))));
        UnsignedEpochSeal evidence = new UnsignedEpochSeal(material, derived, NOW);
        return new SignedEpochSeal(
                evidence, new AuditSignature("kms-v1", "ECDSA", new byte[] {7}, "request-1", NOW));
    }

    private static ActorContext actor() {
        return ActorContext.tenantWorkforce(
                new ActorId("auditor-1"),
                TENANT,
                CorrelationId.parse("01K74Q5Y7B0000000000000000"),
                SourceIp.parse("127.0.0.1"));
    }

    private static AuditHash hash(int marker) {
        byte[] value = new byte[32];
        Arrays.fill(value, (byte) marker);
        return new AuditHash((short) 1, value);
    }
}
