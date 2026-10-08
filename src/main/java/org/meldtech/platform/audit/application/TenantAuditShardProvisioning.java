package org.meldtech.platform.audit.application;

import java.time.Instant;
import java.time.YearMonth;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;

/** Stable provisioning interface consumed by FEAT-TENANT-001 before tenant writes are enabled. */
public interface TenantAuditShardProvisioning {

    Mono<AuditShardPolicy> provisionDefault(
            TenantId tenantId, YearMonth firstEpoch, Instant recordedAt);

    Mono<AuditShardPolicy> changeAtEpochBoundary(
            TenantId tenantId,
            int newShardCount,
            YearMonth effectivePeriod,
            long policyVersion,
            ActorContext actor,
            Instant recordedAt);
}
