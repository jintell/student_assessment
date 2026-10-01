package org.meldtech.platform.outbox.api;

import java.util.Objects;
import java.util.UUID;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.identity.TenantId;

public record DeadLetterRedrive(
        UUID requestId,
        TenantId tenantId,
        ActorContext actor,
        UUID outboxEventId,
        String eventType,
        String reason,
        DeadLetterKind deadLetterKind) {

    public DeadLetterRedrive {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(outboxEventId, "outboxEventId");
        Objects.requireNonNull(eventType, "eventType");
        FailedOutboxRedrive.validateReason(reason);
        Objects.requireNonNull(deadLetterKind, "deadLetterKind");
    }
}
