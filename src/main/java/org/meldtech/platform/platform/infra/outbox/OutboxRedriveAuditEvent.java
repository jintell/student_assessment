package org.meldtech.platform.platform.infra.outbox;

import java.util.Objects;
import java.util.UUID;
import org.meldtech.platform.audit.api.AuditEvent;
import org.meldtech.platform.outbox.api.RedriveResult;
import org.meldtech.platform.shared.kernel.context.ActorContext;

record OutboxRedriveAuditEvent(
        UUID requestId,
        UUID outboxEventId,
        ActorContext actor,
        String reason,
        String sourceKind,
        RedriveResult outcome)
        implements AuditEvent {

    OutboxRedriveAuditEvent {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(outboxEventId, "outboxEventId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(outcome, "outcome");
    }
}
