package org.meldtech.platform.shared.kernel.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BusinessEventTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-10-03T12:00:00Z");

    @Test
    void exposesExactlyTheApprovedMvpEventCodes() {
        assertEquals(6, BusinessEventCode.values().length);
        assertEquals(8, PinValidationOutcome.values().length);
    }

    @Test
    void requiresTheBoundedOutcomeOnlyForPinValidation() {
        BusinessEvent started = BusinessEvent.occurred(BusinessEventCode.EXAM_STARTED, OCCURRED_AT);
        BusinessEvent pin = BusinessEvent.pinValidation(OCCURRED_AT, PinValidationOutcome.SUCCESS);

        assertEquals(Optional.empty(), started.pinValidationOutcome());
        assertEquals(Optional.of(PinValidationOutcome.SUCCESS), pin.pinValidationOutcome());
        assertThrows(
                IllegalArgumentException.class,
                () -> BusinessEvent.occurred(BusinessEventCode.PIN_VALIDATION, OCCURRED_AT));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BusinessEvent(
                                BusinessEventCode.EXAM_FINISHED,
                                OCCURRED_AT,
                                Optional.of(PinValidationOutcome.SUCCESS)));
    }
}
