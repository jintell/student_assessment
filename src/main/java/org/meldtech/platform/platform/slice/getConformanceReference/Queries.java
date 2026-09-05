package org.meldtech.platform.platform.slice.getConformanceReference;

import java.util.List;
import org.meldtech.platform.shared.api.RequestTenantId;
import org.meldtech.platform.shared.api.TenantScopedQuery;
import reactor.core.publisher.Mono;

public interface Queries extends TenantScopedQuery {

    Mono<ConformanceMetadata> load(RequestTenantId tenantId);

    record ConformanceMetadata(
            String applicationVersion,
            String architectureVersion,
            String architectureCommit,
            int contextModuleCount,
            int platformModuleCount,
            List<String> rules) {

        public ConformanceMetadata {
            rules = List.copyOf(rules);
        }
    }
}
