package org.meldtech.platform.platform.infra.outbox;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.meldtech.platform.shared.kernel.outbox.OutboxMessage;
import org.meldtech.platform.shared.kernel.outbox.OutboxWriter;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

public final class InMemoryOutboxWriter implements OutboxWriter {

    private final List<RecordedEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public Publisher<Void> append(TenantId tenantId, ActorContext actor, OutboxMessage message) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(message, "message");
        if (!actor.correlationId().equals(message.correlationId())) {
            return Mono.error(new IllegalArgumentException("Correlation context mismatch"));
        }
        return Mono.fromRunnable(() -> events.add(new RecordedEvent(tenantId, actor, message)));
    }

    public List<RecordedEvent> events() {
        return List.copyOf(events);
    }

    public record RecordedEvent(TenantId tenantId, ActorContext actor, OutboxMessage message) {

        public RecordedEvent {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(message, "message");
        }
    }
}
