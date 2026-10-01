package org.meldtech.platform.platform.infra.outbox;

enum ProcessedEventOutcome {
    APPLIED,
    BUSINESS_DUPLICATE,
    STALE_VERSION,
    DELIVERY_DUPLICATE
}
