package org.meldtech.platform.conformance.fixtures.r5;

import org.meldtech.platform.shared.api.TenantScopedQuery;
import reactor.core.publisher.Mono;

public final class Queries implements TenantScopedQuery {

    public Mono<Void> load() {
        return Mono.empty();
    }
}
