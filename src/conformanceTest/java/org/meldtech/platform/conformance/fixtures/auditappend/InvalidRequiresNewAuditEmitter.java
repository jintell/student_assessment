package org.meldtech.platform.conformance.fixtures.auditappend;

import java.time.Instant;
import org.meldtech.platform.shared.kernel.audit.AuditEmitter;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.reactivestreams.Publisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

public final class InvalidRequiresNewAuditEmitter implements AuditEmitter {
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Publisher<Void> emit(AuditEvent event, ActorContext actor, Instant occurredAt) {
        return Mono.empty();
    }
}
