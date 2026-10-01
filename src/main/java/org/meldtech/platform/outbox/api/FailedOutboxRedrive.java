package org.meldtech.platform.outbox.api;

import java.util.Objects;
import java.util.UUID;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.identity.TenantId;

public record FailedOutboxRedrive(
        UUID requestId, TenantId tenantId, ActorContext actor, UUID outboxEventId, String reason) {

    public FailedOutboxRedrive {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(outboxEventId, "outboxEventId");
        validateReason(reason);
    }

    static void validateReason(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank() || reason.length() > 256) {
            throw new IllegalArgumentException("Redrive reason must contain 1 to 256 characters");
        }
    }
}
