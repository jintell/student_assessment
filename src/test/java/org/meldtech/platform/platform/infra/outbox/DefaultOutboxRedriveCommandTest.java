package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.audit.api.AuditEvent;
import org.meldtech.platform.outbox.api.DeadLetterKind;
import org.meldtech.platform.outbox.api.DeadLetterRedrive;
import org.meldtech.platform.outbox.api.FailedOutboxRedrive;
import org.meldtech.platform.outbox.api.RedriveResult;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.springframework.transaction.reactive.TransactionContextManager;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DefaultOutboxRedriveCommandTest {

    private static final TenantId TENANT = TenantId.parse("01950f47-6000-7000-8000-000000000001");
    private static final UUID EVENT_ID = UUID.fromString("01950f47-6000-7000-8000-000000000002");

    @Test
    void deadLetterRedriveRechecksConsumerGuardBeforeRepublishing() {
        AtomicInteger publications = new AtomicInteger();
        List<AuditEvent> auditEvents = new ArrayList<>();
        DefaultOutboxRedriveCommand command =
                new DefaultOutboxRedriveCommand(
                        (tenantId, eventId) -> Mono.just(true),
                        (tenantId, eventId) -> Mono.just(true),
                        request -> Mono.just(true),
                        request -> Mono.fromRunnable(publications::incrementAndGet),
                        (tenantId, event) -> Mono.fromRunnable(() -> auditEvents.add(event)));

        StepVerifier.create(command.redriveDeadLetter(request()))
                .expectNext(RedriveResult.ALREADY_PROCESSED)
                .verifyComplete();

        assertThat(publications).hasValue(0);
        assertThat(auditEvents).singleElement().isInstanceOf(OutboxRedriveAuditEvent.class);
    }

    @Test
    void eligibleDeadLetterIsRepublishedAndIneligibleOneIsRetained() {
        AtomicInteger publications = new AtomicInteger();
        List<AuditEvent> auditEvents = new ArrayList<>();
        DefaultOutboxRedriveCommand eligible =
                command(false, true, true, publications, auditEvents);
        DefaultOutboxRedriveCommand ineligible =
                command(false, false, true, publications, auditEvents);

        StepVerifier.create(eligible.redriveDeadLetter(request()))
                .expectNext(RedriveResult.REPUBLISHED)
                .verifyComplete();
        StepVerifier.create(ineligible.redriveDeadLetter(request()))
                .expectNext(RedriveResult.NOT_ELIGIBLE)
                .verifyComplete();

        assertThat(publications).hasValue(1);
        assertThat(auditEvents).hasSize(2);
    }

    @Test
    void failedRowsReportWhetherTheyWereRequeued() {
        List<AuditEvent> auditEvents = new ArrayList<>();
        FailedOutboxRedrive request =
                new FailedOutboxRedrive(
                        UUID.fromString("01950f47-6000-7000-8000-000000000003"),
                        TENANT,
                        actor(),
                        EVENT_ID,
                        "operator reviewed failure");
        DefaultOutboxRedriveCommand requeued =
                command(false, false, true, new AtomicInteger(), auditEvents);
        DefaultOutboxRedriveCommand retained =
                command(false, false, false, new AtomicInteger(), auditEvents);

        StepVerifier.create(
                        requeued.redriveFailed(request)
                                .contextWrite(TransactionContextManager.createTransactionContext()))
                .expectNext(RedriveResult.REQUEUED)
                .verifyComplete();
        StepVerifier.create(
                        retained.redriveFailed(request)
                                .contextWrite(TransactionContextManager.createTransactionContext()))
                .expectNext(RedriveResult.NOT_ELIGIBLE)
                .verifyComplete();

        assertThat(auditEvents).hasSize(2);
    }

    private static DefaultOutboxRedriveCommand command(
            boolean processed,
            boolean eligible,
            boolean requeued,
            AtomicInteger publications,
            List<AuditEvent> auditEvents) {
        return new DefaultOutboxRedriveCommand(
                (tenantId, eventId) -> Mono.just(requeued),
                (tenantId, eventId) -> Mono.just(processed),
                request -> Mono.just(eligible),
                request -> Mono.fromRunnable(publications::incrementAndGet),
                (tenantId, event) -> Mono.fromRunnable(() -> auditEvents.add(event)));
    }

    private static DeadLetterRedrive request() {
        return new DeadLetterRedrive(
                UUID.fromString("01950f47-6000-7000-8000-000000000003"),
                TENANT,
                actor(),
                EVENT_ID,
                "platform.ReferenceEvent.v1",
                "consumer deployed",
                DeadLetterKind.UNHANDLED_EVENT_VERSION);
    }

    private static ActorContext actor() {
        return ActorContext.tenantWorkforce(
                new ActorId("operator-1"),
                TENANT,
                CorrelationId.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                SourceIp.parse("127.0.0.1"));
    }
}
