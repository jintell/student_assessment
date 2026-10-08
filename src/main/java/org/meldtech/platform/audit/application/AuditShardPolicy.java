package org.meldtech.platform.audit.application;

import java.time.YearMonth;
import java.util.Objects;
import org.meldtech.platform.shared.kernel.identity.TenantId;

public record AuditShardPolicy(
        TenantId tenantId, YearMonth effectivePeriod, int shardCount, long policyVersion) {

    public static final int DEFAULT_SHARD_COUNT = 64;
    public static final int MAX_SHARD_COUNT = 1024;

    public AuditShardPolicy {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(effectivePeriod, "effectivePeriod");
        if (shardCount < 1 || shardCount > MAX_SHARD_COUNT) {
            throw new IllegalArgumentException("shardCount must be between 1 and 1024");
        }
        if (policyVersion <= 0) {
            throw new IllegalArgumentException("policyVersion must be positive");
        }
    }
}
