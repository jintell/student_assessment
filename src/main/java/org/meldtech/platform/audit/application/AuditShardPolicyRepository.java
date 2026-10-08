package org.meldtech.platform.audit.application;

import java.time.Instant;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;

public interface AuditShardPolicyRepository {

    Mono<Void> provision(AuditShardPolicy policy, Instant recordedAt);

    Mono<AuditShardPolicy> current(TenantId tenantId);

    /** Returns false when the target epoch already has provisioned heads or another policy row. */
    Mono<Boolean> appendAtUnopenedEpochBoundary(AuditShardPolicy policy, Instant recordedAt);
}
