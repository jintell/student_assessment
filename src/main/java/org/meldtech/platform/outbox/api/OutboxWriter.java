package org.meldtech.platform.outbox.api;

import org.meldtech.platform.shared.api.RequestTenantId;
import reactor.core.publisher.Mono;

/** The only port for asynchronous cross-module state propagation. */
public interface OutboxWriter {

    Mono<Void> append(RequestTenantId tenantId, IntegrationEvent event);
}
