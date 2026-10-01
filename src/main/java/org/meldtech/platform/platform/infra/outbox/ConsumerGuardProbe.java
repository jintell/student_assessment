package org.meldtech.platform.platform.infra.outbox;

import java.util.UUID;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;

interface ConsumerGuardProbe {

    Mono<Boolean> wasProcessed(TenantId tenantId, UUID eventId);
}
