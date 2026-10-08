package org.meldtech.platform.audit.application;

import reactor.core.publisher.Mono;

public interface AuditDispositionOperations {

    Mono<Void> freezeAndVerifyShardEvidence(DispositionRequest request);

    Mono<Void> verifySealInDenseRootChain(DispositionRequest request);

    Mono<Void> emitAndVerifyDispositionEvidence(DispositionRequest request);

    Mono<Void> recheckPolicyAndDisposePartition(DispositionRequest request);

    Mono<Void> verifyRetainedEvidence(DispositionRequest request);

    Mono<Void> recordProgress(
            DispositionRequest request, AuditDispositionExecutor.Step completedStep);
}
