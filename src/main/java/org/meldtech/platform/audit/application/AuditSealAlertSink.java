package org.meldtech.platform.audit.application;

import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.shared.kernel.identity.TenantId;

@FunctionalInterface
public interface AuditSealAlertSink {

    void sealDeferred(TenantId tenantId, EpochIdentity epoch, String reasonCode);
}
