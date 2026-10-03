package org.meldtech.platform.platform.infra.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.ActorType;
import org.meldtech.platform.shared.kernel.context.CorrelationId;

class StructuredLogEventTest {

    private static final CorrelationId CORRELATION_ID =
            CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV");

    @Test
    void constructsAClosedRequestCompletionEvent() {
        StructuredLogEvent event = event(Optional.of(12L), Optional.of(2L));

        assertEquals("platform", event.module());
        assertEquals(Optional.of(2L), event.dbQueryCount());
    }

    @Test
    void enforcesCoupledConditionalFieldsAndSingleLineMessages() {
        assertThrows(
                IllegalArgumentException.class, () -> event(Optional.of(12L), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new StructuredLogEvent(
                                Instant.parse("2026-10-03T12:00:00Z"),
                                StructuredLogEvent.Level.INFO,
                                "example.Logger",
                                "first\nsecond",
                                CORRELATION_ID,
                                "0123456789abcdef0123456789abcdef",
                                "0123456789abcdef",
                                StructuredLogEvent.RuntimeRole.API,
                                "platform",
                                "getConformanceReference",
                                Optional.of(ActorType.WORKFORCE_USER),
                                Optional.of(new ActorId("staff-7")),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty()));
    }

    private static StructuredLogEvent event(Optional<Long> duration, Optional<Long> queryCount) {
        return new StructuredLogEvent(
                Instant.parse("2026-10-03T12:00:00Z"),
                StructuredLogEvent.Level.INFO,
                "example.Logger",
                "Request completed",
                CORRELATION_ID,
                "0123456789abcdef0123456789abcdef",
                "0123456789abcdef",
                StructuredLogEvent.RuntimeRole.API,
                "platform",
                "getConformanceReference",
                Optional.of(ActorType.WORKFORCE_USER),
                Optional.of(new ActorId("staff-7")),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                duration,
                queryCount,
                Optional.empty());
    }
}
