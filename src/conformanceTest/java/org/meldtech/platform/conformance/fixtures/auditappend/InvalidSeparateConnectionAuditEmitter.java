package org.meldtech.platform.conformance.fixtures.auditappend;

import io.r2dbc.spi.ConnectionFactory;
import java.time.Instant;
import org.meldtech.platform.shared.kernel.audit.AuditEmitter;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

public final class InvalidSeparateConnectionAuditEmitter implements AuditEmitter {
    private final ConnectionFactory connections;

    public InvalidSeparateConnectionAuditEmitter(ConnectionFactory connections) {
        this.connections = connections;
    }

    @Override
    public Publisher<Void> emit(AuditEvent event, ActorContext actor, Instant occurredAt) {
        return Mono.from(connections.create()).then();
    }
}
