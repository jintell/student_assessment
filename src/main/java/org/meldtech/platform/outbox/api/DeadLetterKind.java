package org.meldtech.platform.outbox.api;

public enum DeadLetterKind {
    UNHANDLED_EVENT_VERSION,
    POISON_PAYLOAD
}
