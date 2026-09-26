package org.meldtech.platform.shared.kernel.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.identity.OutboxEventId;

public record OutboxMessage(
        OutboxEventId eventId,
        String eventType,
        AggregateReference aggregate,
        IntegrationEvent payload,
        CorrelationId correlationId,
        Instant occurredAt) {

    private static final Pattern VERSIONED_EVENT_TYPE =
            Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*\\.v[1-9][0-9]*");

    public OutboxMessage {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (!VERSIONED_EVENT_TYPE.matcher(eventType).matches()) {
            throw new IllegalArgumentException("eventType must be a stable versioned name");
        }
    }
}
