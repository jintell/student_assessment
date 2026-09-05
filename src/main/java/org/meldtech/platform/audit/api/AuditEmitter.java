package org.meldtech.platform.audit.api;

import org.meldtech.platform.shared.api.RequestTenantId;
import reactor.core.publisher.Mono;

/** Port for appending an audit event inside the caller's transaction. */
public interface AuditEmitter {

    Mono<Void> emit(RequestTenantId tenantId, AuditEvent event);
}
