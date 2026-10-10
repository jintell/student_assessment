package org.meldtech.platform.audit.infra;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Statement;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.meldtech.platform.audit.application.AuditDispositionEligibility;
import org.meldtech.platform.audit.application.AuditDispositionEvidenceEmitter;
import org.meldtech.platform.audit.application.AuditDispositionExecutor;
import org.meldtech.platform.audit.application.AuditDispositionOperations;
import org.meldtech.platform.audit.application.AuditFullVerifier;
import org.meldtech.platform.audit.application.AuditSignatureVerifier;
import org.meldtech.platform.audit.application.DispositionRequest;
import org.meldtech.platform.audit.application.OpenAuditChain;
import org.meldtech.platform.audit.domain.AuditChainWalk;
import org.meldtech.platform.audit.domain.AuditHash;
import org.meldtech.platform.audit.domain.AuditRootChainValidator;
import org.meldtech.platform.audit.domain.AuditVerificationMismatch;
import org.meldtech.platform.audit.domain.CanonicalJsonCodec;
import org.meldtech.platform.platform.api.TransactionalCollaboration;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** PostgreSQL execution of the ordered audit disposition protocol. */
public final class R2dbcAuditDispositionOperations implements AuditDispositionOperations {

    private static final String VERIFY_DISPOSITION_EVENT_SQL =
            """
            SELECT count(*) AS evidence_count
            FROM audit.audit_event
            WHERE tenant_id = $1
              AND event_type = $2
              AND payload ->> 'request_id' = $3
            """;

    private static final String PROGRESS_SQL =
            """
            INSERT INTO audit.audit_disposition_lifecycle (
                request_id, tenant_id, retention_class, period, policy_key, policy_version,
                state, original_retention_start, original_due_at, last_completed_step, updated_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, clock_timestamp())
            ON CONFLICT (request_id) DO UPDATE
            SET state = EXCLUDED.state,
                last_completed_step = EXCLUDED.last_completed_step,
                updated_at = EXCLUDED.updated_at
            WHERE audit.audit_disposition_lifecycle.tenant_id = EXCLUDED.tenant_id
              AND audit.audit_disposition_lifecycle.retention_class = EXCLUDED.retention_class
              AND audit.audit_disposition_lifecycle.period = EXCLUDED.period
              AND audit.audit_disposition_lifecycle.policy_key = EXCLUDED.policy_key
              AND audit.audit_disposition_lifecycle.policy_version = EXCLUDED.policy_version
            """;

    private final ConnectionFactory connectionFactory;
    private final R2dbcAuditFullVerificationEvidence evidence;
    private final AuditSignatureVerifier signatures;
    private final AuditRootChainValidator rootChains;
    private final CanonicalJsonCodec codec;
    private final AuditFullVerifier fullVerifier;
    private final AuditDispositionEvidenceEmitter dispositionEvidence;
    private final TransactionalCollaboration transactions;
    private final AuditDispositionEligibility eligibility;
    private final ActorContext actor;
    private final Clock clock;

    public R2dbcAuditDispositionOperations(
            ConnectionFactory connectionFactory,
            R2dbcAuditFullVerificationEvidence evidence,
            AuditSignatureVerifier signatures,
            AuditRootChainValidator rootChains,
            CanonicalJsonCodec codec,
            AuditFullVerifier fullVerifier,
            AuditDispositionEvidenceEmitter dispositionEvidence,
            TransactionalCollaboration transactions,
            AuditDispositionEligibility eligibility,
            ActorContext actor,
            Clock clock) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.signatures = Objects.requireNonNull(signatures, "signatures");
        this.rootChains = Objects.requireNonNull(rootChains, "rootChains");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.fullVerifier = Objects.requireNonNull(fullVerifier, "fullVerifier");
        this.dispositionEvidence =
                Objects.requireNonNull(dispositionEvidence, "dispositionEvidence");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Mono<Void> freezeAndVerifyShardEvidence(DispositionRequest request) {
        Objects.requireNonNull(request, "request");
        return evidence.retainedChains(request.epoch())
                .switchIfEmpty(Mono.error(new AuditVerificationMismatch("epoch has no evidence")))
                .concatMap(this::verifyChain)
                .then();
    }

    @Override
    public Mono<Void> verifySealInDenseRootChain(DispositionRequest request) {
        Objects.requireNonNull(request, "request");
        return evidence.rootEvidence()
                .flatMap(
                        roots -> {
                            rootChains.validate(
                                    roots.tenantId(), roots.seals(), roots.currentHead());
                            return evidence.seal(request.epoch());
                        })
                .flatMap(
                        seal ->
                                signatures.verify(
                                        seal.evidence().signingMessage(codec), seal.signature()))
                .flatMap(
                        valid ->
                                valid
                                        ? Mono.empty()
                                        : Mono.error(
                                                new AuditVerificationMismatch(
                                                        "audit epoch seal signature is invalid")));
    }

    @Override
    public Mono<Void> emitAndVerifyDispositionEvidence(DispositionRequest request) {
        Objects.requireNonNull(request, "request");
        return transactions
                .inExamEntryTransaction(
                        request.tenantId(),
                        ignored ->
                                evidence.seal(request.epoch())
                                        .flatMap(
                                                seal ->
                                                        dispositionEvidence.emit(
                                                                request,
                                                                seal,
                                                                actor,
                                                                clock.instant())))
                .then(verifyDispositionEvent(request));
    }

    @Override
    public Mono<Void> recheckPolicyAndDisposePartition(DispositionRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.dueAt().isAfter(clock.instant())) {
            return Mono.error(new IllegalStateException("audit epoch is not yet due"));
        }
        return eligibility
                .remainsEligible(request)
                .flatMap(
                        eligible ->
                                eligible
                                        ? detach(request)
                                        : Mono.error(
                                                new IllegalStateException(
                                                        "audit epoch disposition is no longer eligible")));
    }

    @Override
    public Mono<Void> verifyRetainedEvidence(DispositionRequest request) {
        Objects.requireNonNull(request, "request");
        return fullVerifier.verify(AuditFullVerifier.Trigger.QUARTERLY).then();
    }

    @Override
    public Mono<Void> recordProgress(
            DispositionRequest request, AuditDispositionExecutor.Step completedStep) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(completedStep, "completedStep");
        return withConnection(
                connection -> {
                    String state =
                            completedStep
                                            == AuditDispositionExecutor.Step
                                                    .RETAINED_EVIDENCE_VERIFIED
                                    ? "COMPLETED"
                                    : "IN_PROGRESS";
                    Statement statement =
                            connection
                                    .createStatement(PROGRESS_SQL)
                                    .bind(0, request.requestId())
                                    .bind(1, UUID.fromString(request.tenantId().toString()))
                                    .bind(2, request.epoch().retentionClass().name())
                                    .bind(3, request.epoch().period().atDay(1))
                                    .bind(4, request.policyKey())
                                    .bind(5, request.policyVersion())
                                    .bind(6, state)
                                    .bind(7, request.originalRetentionStart())
                                    .bind(8, request.dueAt())
                                    .bind(9, completedStep.name());
                    return Flux.from(statement.execute())
                            .flatMap(result -> result.getRowsUpdated())
                            .single()
                            .flatMap(
                                    changed ->
                                            changed == 1
                                                    ? Mono.empty()
                                                    : Mono.error(
                                                            new IllegalStateException(
                                                                    "disposition progress identity changed")));
                });
    }

    private Mono<Void> verifyChain(OpenAuditChain chain) {
        Map<Long, AuditHash> walkedHeads = new HashMap<>();
        return evidence.records(chain)
                .scan(
                        AuditChainWalk.begin(chain.seed()),
                        (state, record) -> {
                            AuditChainWalk.State next = AuditChainWalk.append(state, record);
                            walkedHeads.put(record.sequence(), record.recordHash());
                            return next;
                        })
                .last()
                .doOnNext(
                        state -> {
                            AuditChainWalk.finish(
                                    state, chain.committedSequence(), chain.committedHead());
                            chain.checkpoints()
                                    .forEach(
                                            checkpoint -> {
                                                AuditHash walked =
                                                        walkedHeads.get(checkpoint.sequenceEnd());
                                                if (walked == null
                                                        || !checkpoint.headHash().equals(walked)) {
                                                    throw new AuditVerificationMismatch(
                                                            "checkpoint does not match epoch chain");
                                                }
                                            });
                        })
                .thenMany(Flux.fromIterable(chain.checkpoints()))
                .concatMap(
                        checkpoint ->
                                signatures
                                        .verify(checkpoint.signingMessage(), checkpoint.signature())
                                        .flatMap(
                                                valid ->
                                                        valid
                                                                ? Mono.empty()
                                                                : Mono.error(
                                                                        new AuditVerificationMismatch(
                                                                                "checkpoint signature is invalid"))))
                .then();
    }

    private Mono<Void> verifyDispositionEvent(DispositionRequest request) {
        return withConnection(
                connection -> {
                    Statement statement =
                            connection
                                    .createStatement(VERIFY_DISPOSITION_EVENT_SQL)
                                    .bind(0, UUID.fromString(request.tenantId().toString()))
                                    .bind(1, AuditDispositionEvidenceEmitter.EVENT_TYPE)
                                    .bind(2, request.requestId().toString());
                    return Flux.from(statement.execute())
                            .flatMap(
                                    result ->
                                            result.map(
                                                    (row, metadata) ->
                                                            Objects.requireNonNull(
                                                                    row.get(
                                                                            "evidence_count",
                                                                            Long.class))))
                            .single()
                            .flatMap(
                                    count ->
                                            count == 1
                                                    ? Mono.empty()
                                                    : Mono.error(
                                                            new AuditVerificationMismatch(
                                                                    "disposition evidence was not "
                                                                            + "committed exactly once")));
                });
    }

    private Mono<Void> detach(DispositionRequest request) {
        PartitionNames names =
                partitionNames(request.epoch().retentionClass(), request.epoch().period());
        String sql =
                "ALTER TABLE audit." + names.parent() + " DETACH PARTITION audit." + names.leaf();
        return withConnection(
                connection ->
                        Flux.from(connection.createStatement(sql).execute())
                                .flatMap(result -> result.getRowsUpdated())
                                .then());
    }

    private static PartitionNames partitionNames(
            RetentionClass retentionClass, java.time.YearMonth period) {
        String token =
                switch (retentionClass) {
                    case RESULT_CORRECTION_EVIDENCE -> "result_correction";
                    case RESULT_PUBLICATION_EVIDENCE -> "result_publication";
                    case PIN_SECURITY_EVENT -> "pin_security";
                    case GENERAL_AUDIT_EVENT -> "general";
                };
        String parent = "audit_event_p_" + token;
        String month =
                period.getMonthValue() < 10
                        ? "0" + period.getMonthValue()
                        : Integer.toString(period.getMonthValue());
        String leaf = parent + "_y" + period.getYear() + "m" + month;
        return new PartitionNames(parent, leaf);
    }

    private <T> Mono<T> withConnection(Function<Connection, ? extends Publisher<T>> work) {
        return Mono.usingWhen(
                Mono.from(connectionFactory.create()),
                connection -> Mono.from(work.apply(connection)),
                connection -> Mono.from(connection.close()),
                (connection, failure) -> Mono.from(connection.close()),
                connection -> Mono.from(connection.close()));
    }

    private record PartitionNames(String parent, String leaf) {}
}
