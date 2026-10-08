package org.meldtech.platform.audit.slice.getComplianceAuditEvents;

import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;

@FunctionalInterface
public interface AuditComplianceCapabilityView {

    Mono<Boolean> hasCapability(ActorId actorId, TenantId tenantId, String capability);
}
