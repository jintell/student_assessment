package org.meldtech.platform.platform.api;

/** Ownership cannot be proved; no further batch may be started. */
public final class SweepLockLostException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public SweepLockLostException(Throwable cause) {
        super("Sweep advisory lock is no longer held", cause);
    }

    public SweepLockLostException() {
        super("Sweep advisory lock is no longer held");
    }
}
