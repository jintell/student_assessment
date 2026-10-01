package org.meldtech.platform.platform.infra.outbox;

import java.util.Objects;
import org.meldtech.platform.audit.api.AuditEmitter;
import org.meldtech.platform.outbox.api.DeadLetterRedrive;
import org.meldtech.platform.outbox.api.FailedOutboxRedrive;
import org.meldtech.platform.outbox.api.OutboxRedriveCommand;
import org.meldtech.platform.outbox.api.RedriveResult;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;

final class DefaultOutboxRedriveCommand implements OutboxRedriveCommand {

    private final OutboxRedriveRepository repository;
    private final ConsumerGuardProbe consumerGuard;
    private final RedriveEligibility eligibility;
    private final DeadLetterRedriveGateway deadLetters;
    private final AuditEmitter auditEmitter;

    DefaultOutboxRedriveCommand(
            OutboxRedriveRepository repository,
            ConsumerGuardProbe consumerGuard,
            RedriveEligibility eligibility,
            DeadLetterRedriveGateway deadLetters,
            AuditEmitter auditEmitter) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.consumerGuard = Objects.requireNonNull(consumerGuard, "consumerGuard");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.deadLetters = Objects.requireNonNull(deadLetters, "deadLetters");
        this.auditEmitter = Objects.requireNonNull(auditEmitter, "auditEmitter");
    }

    @Override
    public Mono<RedriveResult> redriveFailed(FailedOutboxRedrive request) {
        Objects.requireNonNull(request, "request");
        return TransactionSynchronizationManager.forCurrentTransaction()
                .then(repository.requeueFailed(request.tenantId(), request.outboxEventId()))
                .map(requeued -> requeued ? RedriveResult.REQUEUED : RedriveResult.NOT_ELIGIBLE)
                .flatMap(
                        outcome ->
                                audit(
                                                request.requestId(),
                                                request.outboxEventId(),
                                                request.actor(),
                                                request.reason(),
                                                "FAILED_ROW",
                                                outcome,
                                                request.tenantId())
                                        .thenReturn(outcome));
    }

    @Override
    public Mono<RedriveResult> redriveDeadLetter(DeadLetterRedrive request) {
        Objects.requireNonNull(request, "request");
        return consumerGuard
                .wasProcessed(request.tenantId(), request.outboxEventId())
                .flatMap(
                        processed ->
                                processed
                                        ? Mono.just(RedriveResult.ALREADY_PROCESSED)
                                        : eligibility
                                                .isEligible(request)
                                                .flatMap(
                                                        eligible ->
                                                                eligible
                                                                        ? deadLetters
                                                                                .republishAndAcknowledge(request)
                                                                                .thenReturn(RedriveResult.REPUBLISHED)
                                                                        : Mono.just(RedriveResult.NOT_ELIGIBLE)))
                .flatMap(
                        outcome ->
                                audit(
                                                request.requestId(),
                                                request.outboxEventId(),
                                                request.actor(),
                                                request.reason(),
                                                request.deadLetterKind().name(),
                                                outcome,
                                                request.tenantId())
                                        .thenReturn(outcome));
    }

    private Mono<Void> audit(
            java.util.UUID requestId,
            java.util.UUID eventId,
            org.meldtech.platform.shared.kernel.context.ActorContext actor,
            String reason,
            String source,
            RedriveResult outcome,
            org.meldtech.platform.shared.kernel.identity.TenantId tenantId) {
        return auditEmitter.emit(
                tenantId,
                new OutboxRedriveAuditEvent(requestId, eventId, actor, reason, source, outcome));
    }
}
