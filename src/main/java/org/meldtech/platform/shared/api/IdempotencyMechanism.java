package org.meldtech.platform.shared.api;

public enum IdempotencyMechanism {
    POSTGRES_UNIQUE,
    DURABLE_STATE_GUARD,
    REDIS_HEADER,
    NOT_APPLICABLE
}
