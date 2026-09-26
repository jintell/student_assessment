package org.meldtech.platform.shared.kernel.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.identity.OutboxEventId;

class OutboxMessageTest {

    @Test
    void carriesTypedIdentityCorrelationAndOccurrenceTime() {
        CorrelationId correlationId = CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV");
        Instant occurredAt = Instant.parse("2026-09-25T10:15:30Z");
        OutboxMessage message =
                new OutboxMessage(
                        OutboxEventId.parse("018f3f1e-7b2a-7cc5-98c4-2c11e17c4698"),
                        "assessment.published.v1",
                        new AggregateReference(
                                "Assessment", "018f3f1e-7b2a-7cc5-98c4-2c11e17c4698"),
                        new PublishedEvent(),
                        correlationId,
                        occurredAt);

        assertEquals(correlationId, message.correlationId());
        assertEquals(occurredAt, message.occurredAt());
    }

    @Test
    void rejectsUnversionedEventTypes() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new OutboxMessage(
                                OutboxEventId.parse("018f3f1e-7b2a-7cc5-98c4-2c11e17c4698"),
                                "assessment.published",
                                new AggregateReference("Assessment", "assessment-1"),
                                new PublishedEvent(),
                                CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV"),
                                Instant.parse("2026-09-25T10:15:30Z")));
    }

    private record PublishedEvent() implements IntegrationEvent {}
}
