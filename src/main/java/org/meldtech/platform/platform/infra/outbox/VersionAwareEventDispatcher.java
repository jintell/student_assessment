package org.meldtech.platform.platform.infra.outbox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

final class VersionAwareEventDispatcher {

    private final Map<EventType, EventConsumer> consumers;
    private final DeadLetterPublisher deadLetters;
    private final DeadLetterAlertSink alerts;

    VersionAwareEventDispatcher(
            List<EventConsumer> consumers,
            DeadLetterPublisher deadLetters,
            DeadLetterAlertSink alerts) {
        this.deadLetters = Objects.requireNonNull(deadLetters, "deadLetters");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        Map<EventType, EventConsumer> registrations = new HashMap<>();
        for (EventConsumer consumer : List.copyOf(consumers)) {
            for (EventType version : consumer.handledVersions()) {
                EventConsumer previous = registrations.putIfAbsent(version, consumer);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "Multiple consumers registered for " + version);
                }
            }
        }
        this.consumers = Map.copyOf(registrations);
    }

    Mono<DispatchResult> dispatch(ConsumerEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        EventConsumer consumer = consumers.get(envelope.event().eventType());
        if (consumer == null) {
            return deadLetters
                    .deadLetter(
                            envelope, DeadLetterPublisher.DeadLetterReason.UNHANDLED_EVENT_VERSION)
                    .then(
                            alerts.deadLettered(
                                    envelope.event().eventId(),
                                    DeadLetterPublisher.DeadLetterReason.UNHANDLED_EVENT_VERSION))
                    .thenReturn(DispatchResult.DEAD_LETTERED_UNHANDLED_VERSION);
        }
        return consumer.handle(envelope).map(ignored -> DispatchResult.HANDLED);
    }

    enum DispatchResult {
        HANDLED,
        DEAD_LETTERED_UNHANDLED_VERSION
    }
}
