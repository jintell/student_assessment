package org.meldtech.platform.platform.infra.outbox;

enum PublicationFailureReason {
    BROKER_NACK,
    CONFIRM_TIMEOUT,
    BROKER_UNAVAILABLE,
    UNCLASSIFIED
}
