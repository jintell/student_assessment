package org.meldtech.platform.audit.application;

import java.util.Objects;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

public final class AuditDispositionExecutor {

    private final AuditDispositionOperations operations;

    public AuditDispositionExecutor(AuditDispositionOperations operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    public Mono<DispositionResult> execute(DispositionRequest request) {
        Objects.requireNonNull(request, "request");
        return run(
                        request,
                        Step.SHARD_EVIDENCE_VERIFIED,
                        () -> operations.freezeAndVerifyShardEvidence(request))
                .then(
                        run(
                                request,
                                Step.SEAL_AND_ROOT_VERIFIED,
                                () -> operations.verifySealInDenseRootChain(request)))
                .then(
                        run(
                                request,
                                Step.DISPOSITION_EVIDENCE_COMMITTED,
                                () -> operations.emitAndVerifyDispositionEvidence(request)))
                .then(
                        run(
                                request,
                                Step.PARTITION_DISPOSED,
                                () -> operations.recheckPolicyAndDisposePartition(request)))
                .then(
                        run(
                                request,
                                Step.RETAINED_EVIDENCE_VERIFIED,
                                () -> operations.verifyRetainedEvidence(request)))
                .thenReturn(
                        new DispositionResult(
                                request.requestId(), Step.RETAINED_EVIDENCE_VERIFIED));
    }

    private Mono<Void> run(DispositionRequest request, Step step, Supplier<Mono<Void>> operation) {
        return Mono.defer(operation)
                .then(Mono.defer(() -> operations.recordProgress(request, step)));
    }

    public enum Step {
        SHARD_EVIDENCE_VERIFIED,
        SEAL_AND_ROOT_VERIFIED,
        DISPOSITION_EVIDENCE_COMMITTED,
        PARTITION_DISPOSED,
        RETAINED_EVIDENCE_VERIFIED
    }

    public record DispositionResult(java.util.UUID requestId, Step completedStep) {

        public DispositionResult {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(completedStep, "completedStep");
        }
    }
}
