package org.meldtech.platform.conformance.fixtures.r8.slice.valid;

import java.time.Instant;
import org.meldtech.platform.shared.kernel.audit.AuditEmitter;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

public class Handler {

    private final AuditEmitter auditEmitter;

    public Handler(AuditEmitter auditEmitter) {
        this.auditEmitter = auditEmitter;
    }

    @Transactional
    public Mono<Void> mutate(AuditEvent event, ActorContext actor, Instant occurredAt) {
        return Mono.from(auditEmitter.emit(event, actor, occurredAt));
    }
}
