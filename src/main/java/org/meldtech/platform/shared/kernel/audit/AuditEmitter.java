package org.meldtech.platform.shared.kernel.audit;

import java.time.Instant;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.reactivestreams.Publisher;

public interface AuditEmitter {

    Publisher<Void> emit(AuditEvent event, ActorContext actor, Instant occurredAt);
}
