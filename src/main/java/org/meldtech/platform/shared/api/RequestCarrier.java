package org.meldtech.platform.shared.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Temporary immutable request context used until FEAT-PLAT-003 supplies TenantId and ActorContext.
 *
 * <p>The definitive types must be adopted together by the request filter, policies, transaction
 * initializer, logging bridge, and outbox reconstruction boundary.
 */
public record RequestCarrier(
        String correlationId,
        Optional<RequestTenantId> tenantId,
        Optional<RequestActor> actor,
        String sourceIp) {

    public RequestCarrier {
        correlationId = requireText(correlationId, "correlationId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(actor, "actor");
        sourceIp = requireText(sourceIp, "sourceIp");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
