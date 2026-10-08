package org.meldtech.platform.audit.application;

import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;

@FunctionalInterface
public interface AuditEpochSealOperation {

    Mono<AuditEpochSealer.SealResult> seal(TenantId tenantId, EpochIdentity epoch);
}
