package org.meldtech.platform.platform.infra.outbox;

import java.util.Objects;

record ConsumerEnvelope(
        ConsumedEvent event, String aggregateId, String payload, String correlationId) {

    ConsumerEnvelope {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
