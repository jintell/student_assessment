package org.meldtech.platform.audit.domain;

import java.time.YearMonth;
import org.meldtech.platform.shared.kernel.identity.TenantId;

@FunctionalInterface
public interface AuditShardCountView {

    int shardCount(TenantId tenantId, YearMonth period);
}
