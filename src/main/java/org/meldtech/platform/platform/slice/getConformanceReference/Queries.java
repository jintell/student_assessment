package org.meldtech.platform.platform.slice.getConformanceReference;

import java.util.List;
import org.meldtech.platform.shared.api.TenantScopedQuery;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;

public interface Queries extends TenantScopedQuery {

    Mono<ConformanceMetadata> load(TenantId tenantId);

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
