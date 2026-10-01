package org.meldtech.platform.platform.infra.outbox;

import java.util.UUID;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;

interface OutboxRedriveRepository {

    Mono<Boolean> requeueFailed(TenantId tenantId, UUID eventId);
}
