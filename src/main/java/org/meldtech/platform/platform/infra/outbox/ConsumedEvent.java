package org.meldtech.platform.platform.infra.outbox;

import java.util.Objects;

record ConsumedEvent(String eventId, String tenantId, EventType eventType) {

    ConsumedEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(eventType, "eventType");
    }
}
