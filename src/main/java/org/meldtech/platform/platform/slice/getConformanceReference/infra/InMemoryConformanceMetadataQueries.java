package org.meldtech.platform.platform.slice.getConformanceReference.infra;

import java.util.List;
import java.util.Objects;
import org.meldtech.platform.platform.slice.getConformanceReference.Queries;
import org.meldtech.platform.shared.api.RequestTenantId;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
final class InMemoryConformanceMetadataQueries implements Queries {

    private static final ConformanceMetadata METADATA =
            new ConformanceMetadata(
                    "0.0.1-SNAPSHOT",
                    "1.4",
                    "aa7fdc5bd7b4a558e3fea19cf9c69b65ed64582b",
                    12,
                    4,
                    List.of("R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8"));

    @Override
    public Mono<ConformanceMetadata> load(RequestTenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return Mono.just(METADATA);
    }
}
