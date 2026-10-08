package org.meldtech.platform.audit.domain;

import java.util.Objects;
import org.meldtech.platform.shared.kernel.identity.TenantId;

public record AuditChainKey(TenantId tenantId, EpochIdentity epoch, int shardId, int shardCount) {

    public AuditChainKey {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(epoch, "epoch");
        AuditHashing.validateShard(shardId, shardCount);
    }
}
