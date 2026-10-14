package org.meldtech.platform.platform.infra.runtime;

/** Sanitized availability failure mapped through the shared problem-detail contract. */
public final class RuntimeUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RuntimeUnavailableException() {
        super("Service temporarily unavailable");
    }
}
