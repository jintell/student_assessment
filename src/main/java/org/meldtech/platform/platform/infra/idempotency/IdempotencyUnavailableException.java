package org.meldtech.platform.platform.infra.idempotency;

public final class IdempotencyUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IdempotencyUnavailableException() {
        super("A trustworthy idempotency decision is unavailable");
    }
}
