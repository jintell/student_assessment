package org.meldtech.platform.shared.kernel.idempotency;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.identity.TenantId;

public record IdempotencyScope(
        String routeId, Optional<TenantId> tenantId, ActorId platformActorId) {

    private static final Pattern ROUTE_ID = Pattern.compile("[a-z][A-Za-z0-9.]{2,127}");

    public IdempotencyScope {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(platformActorId, "platformActorId");
        if (!ROUTE_ID.matcher(routeId).matches()) {
            throw new IllegalArgumentException("routeId must be a stable route identifier");
        }
    }
}
