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
import org.meldtech.platform.outbox.api.RedriveResult;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.TenantId;
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

    private static DeadLetterRedrive request() {
        ActorContext actor =
                ActorContext.tenantWorkforce(
                        new ActorId("operator-1"),
                        TENANT,
                        CorrelationId.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                        SourceIp.parse("127.0.0.1"));
        return new DeadLetterRedrive(
                UUID.fromString("01950f47-6000-7000-8000-000000000003"),
                TENANT,
                actor,
                EVENT_ID,
                "platform.ReferenceEvent.v1",
                "consumer deployed",
                DeadLetterKind.UNHANDLED_EVENT_VERSION);
    }
}
