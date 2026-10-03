package org.meldtech.platform.shared.kernel.observability;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record BusinessEvent(
        BusinessEventCode eventCode,
        Instant occurredAt,
        Optional<PinValidationOutcome> pinValidationOutcome) {

    public BusinessEvent {
        Objects.requireNonNull(eventCode, "eventCode");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(pinValidationOutcome, "pinValidationOutcome");
        boolean pinValidation = eventCode == BusinessEventCode.PIN_VALIDATION;
        if (pinValidation != pinValidationOutcome.isPresent()) {
            throw new IllegalArgumentException(
                    "pinValidationOutcome must be present only for PIN_VALIDATION events");
        }
    }

    public static BusinessEvent occurred(BusinessEventCode eventCode, Instant occurredAt) {
        if (eventCode == BusinessEventCode.PIN_VALIDATION) {
            throw new IllegalArgumentException("PIN_VALIDATION requires a bounded outcome");
        }
        return new BusinessEvent(eventCode, occurredAt, Optional.empty());
    }

    public static BusinessEvent pinValidation(Instant occurredAt, PinValidationOutcome outcome) {
        return new BusinessEvent(
                BusinessEventCode.PIN_VALIDATION,
                occurredAt,
                Optional.of(Objects.requireNonNull(outcome, "outcome")));
    }
}
