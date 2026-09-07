package org.meldtech.platform.platform.slice.r3fixture;

import org.meldtech.platform.shared.api.RequestTenantId;
import org.meldtech.platform.shared.api.TenantScopedQuery;
import reactor.core.publisher.Mono;

public final class Queries implements TenantScopedQuery {

    private static final String SQL =
            "SELECT tenant_id FROM tenancy.institution WHERE tenant_id = :tenantId";

    public Mono<String> load(RequestTenantId tenantId) {
        return Mono.just(SQL + tenantId.value());
    }
}
