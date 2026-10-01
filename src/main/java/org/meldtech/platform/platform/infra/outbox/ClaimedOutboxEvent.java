package org.meldtech.platform.platform.infra.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

record ClaimedOutboxEvent(
        String eventId,
        String tenantId,
        String aggregateType,
        String aggregateId,
        EventType eventType,
        String payload,
        String correlationId,
        Optional<String> traceparent,
        Optional<String> tracestate,
        int attemptCount,
        String claimedBy,
        Instant occurredAt,
        Instant createdAt) {

    ClaimedOutboxEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(traceparent, "traceparent");
        Objects.requireNonNull(tracestate, "tracestate");
        Objects.requireNonNull(claimedBy, "claimedBy");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
