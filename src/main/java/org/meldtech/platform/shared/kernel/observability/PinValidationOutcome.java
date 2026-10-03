package org.meldtech.platform.shared.kernel.observability;

public enum PinValidationOutcome {
    SUCCESS,
    INVALID,
    LOCKED_OUT,
    EXPIRED,
    ALREADY_USED,
    NOT_ISSUED,
    RATE_LIMITED,
    ERROR
}
