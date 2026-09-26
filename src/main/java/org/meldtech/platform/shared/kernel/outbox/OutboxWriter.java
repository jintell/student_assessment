package org.meldtech.platform.shared.kernel.outbox;

import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.reactivestreams.Publisher;

/** Appends one integration event on the caller-owned transaction. */
@FunctionalInterface
public interface OutboxWriter {

    Publisher<Void> append(TenantId tenantId, ActorContext actor, OutboxMessage message);
}
