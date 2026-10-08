package org.meldtech.platform.conformance.fixtures.auditappend;

import io.r2dbc.spi.ConnectionFactory;
import java.time.Instant;
import org.meldtech.platform.shared.kernel.audit.AuditEmitter;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.reactivestreams.Publisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

public final class InvalidAsyncAuditEmitter implements AuditEmitter {

    private final ConnectionFactory connectionFactory;

    public InvalidAsyncAuditEmitter(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Publisher<Void> emit(AuditEvent event, ActorContext actor, Instant occurredAt) {
        return Mono.from(connectionFactory.create()).then();
    }
}
