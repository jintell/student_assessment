package org.meldtech.platform.platform.infra.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

final class RelayPublishStep {

    private static final Logger LOGGER = LoggerFactory.getLogger(RelayPublishStep.class);
    private final OutboxBrokerPublisher broker;
    private final OutboxPublicationStore store;
    private final OutboxAlertSink alerts;
    private final Clock clock;
    private final OutboxTelemetry telemetry;

    RelayPublishStep(
            OutboxBrokerPublisher broker,
            OutboxPublicationStore store,
            OutboxAlertSink alerts,
            Clock clock,
            OutboxTelemetry telemetry) {
        this.broker = Objects.requireNonNull(broker, "broker");
        this.store = Objects.requireNonNull(store, "store");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    Mono<Void> publishSequentially(Flux<ClaimedOutboxEvent> events) {
        return events.concatMap(this::publishOne).then();
    }

    Mono<Void> publishOne(ClaimedOutboxEvent event) {
        return broker.publish(event)
                .flatMap(
                        confirmation ->
                                confirmation.acknowledged()
                                        ? store.markPublished(event, Instant.now(clock))
                                                .doOnSuccess(ignored -> telemetry.relayPublished())
                                        : recordFailure(
                                                event, PublicationFailureReason.BROKER_NACK))
                .onErrorResume(
                        ignored ->
                                recordFailure(event, PublicationFailureReason.BROKER_UNAVAILABLE));
    }

    private Mono<Void> recordFailure(ClaimedOutboxEvent event, PublicationFailureReason reason) {
        Instant nextAttemptAt = Instant.now(clock).plus(OutboxPublicationStore.retryDelay(event));
        telemetry.relayPublishFailed(reason);
        LOGGER.warn(
                "Outbox publication failed eventId={} attempt={} correlationId={} reason={}",
                event.eventId(),
                event.attemptCount() + 1,
                event.correlationId(),
                reason);
        return store.recordFailure(event, reason, nextAttemptAt)
                .flatMap(
                        transition ->
                                transition.terminal()
                                        ? Mono.fromRunnable(telemetry::eventFailed)
                                                .then(
                                                        alerts.publicationFailed(
                                                                event.eventId(), reason))
                                        : Mono.empty());
    }
}
