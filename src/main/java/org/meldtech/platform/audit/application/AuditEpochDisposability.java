package org.meldtech.platform.audit.application;

import java.util.Objects;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;

public final class AuditEpochDisposability {

    private final AuditSealStatus sealStatus;

    public AuditEpochDisposability(AuditSealStatus sealStatus) {
        this.sealStatus = Objects.requireNonNull(sealStatus, "sealStatus");
    }

    public Mono<Boolean> hasRequiredSeal(TenantId tenantId, EpochIdentity epoch) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(epoch, "epoch");
        return sealStatus.isSealed(tenantId, epoch);
    }
}
