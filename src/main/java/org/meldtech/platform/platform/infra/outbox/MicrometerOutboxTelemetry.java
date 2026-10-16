package org.meldtech.platform.platform.infra.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

final class MicrometerOutboxTelemetry implements OutboxTelemetry {

    private final Counter writerAppended;
    private final Counter relayPublished;
    private final Map<PublicationFailureReason, Counter> publishFailures;
    private final Counter staleClaimsReclaimed;
    private final Counter eventFailed;
    private final Counter consumerDuplicates;
    private final Counter relayTicks;
    private final Timer relayTickDuration;
    private final AtomicReference<BacklogSnapshot> backlog = new AtomicReference<>();

    MicrometerOutboxTelemetry(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        writerAppended =
                counter(
                        registry,
                        "outbox.writer.appended",
                        "Successful outbox appends before transaction commit");
        relayPublished =
                counter(
                        registry,
                        "outbox.relay.published",
                        "Confirmed publications recorded as published");
        staleClaimsReclaimed =
                counter(
                        registry,
                        "outbox.stale.claim.reclaimed",
                        "Expired claims returned to pending");
        eventFailed =
                counter(
                        registry,
                        "outbox.event.failed",
                        "Events entering failed state after exhausted publication attempts");
        consumerDuplicates =
                counter(
                        registry,
                        "outbox.consumer.duplicate",
                        "Deliveries suppressed by the consumer guard");
        relayTicks =
                counter(
                        registry,
                        "outbox.relay.tick",
                        "Terminated relay tick attempts including lock losers, errors and cancellation");
        relayTickDuration =
                Timer.builder("outbox.relay.tick.duration")
                        .description(
                                "Relay tick attempt duration including lock acquisition and release")
                        .register(registry);
        publishFailures = new EnumMap<>(PublicationFailureReason.class);
        for (PublicationFailureReason reason : PublicationFailureReason.values()) {
            publishFailures.put(
                    reason,
                    Counter.builder("outbox.relay.publish.failure")
                            .description("Publication attempts failed by bounded reason")
                            .tag("reason", reason.name())
                            .register(registry));
        }
        for (OutboxState state : OutboxState.values()) {
            Gauge.builder(
                            "outbox.backlog.depth",
                            backlog,
                            snapshot -> {
                                BacklogSnapshot current = snapshot.get();
                                return current == null
                                        ? Double.NaN
                                        : current.depthByState()
                                                .getOrDefault(state, 0L)
                                                .doubleValue();
                            })
                    .description("Cluster-wide outbox row count by state; unknown until sampled")
                    .tag("state", state.name())
                    .register(registry);
        }
        Gauge.builder(
                        "outbox.oldest.pending.age.seconds",
                        backlog,
                        snapshot -> {
                            BacklogSnapshot current = snapshot.get();
                            return current == null
                                    ? Double.NaN
                                    : current.oldestPendingAge().toSeconds();
                        })
                .description(
                        "Age of oldest pending row; zero for a sampled empty backlog, unknown before sampling")
                .register(registry);
    }

    @Override
    public void writerAppended() {
        writerAppended.increment();
    }

    @Override
    public void relayPublished() {
        relayPublished.increment();
    }

    @Override
    public void relayPublishFailed(PublicationFailureReason reason) {
        Objects.requireNonNull(publishFailures.get(reason), "publish failure counter").increment();
    }

    @Override
    public void staleClaimsReclaimed(long count) {
        staleClaimsReclaimed.increment((double) count);
    }

    @Override
    public void eventFailed() {
        eventFailed.increment();
    }

    @Override
    public void consumerDuplicate() {
        consumerDuplicates.increment();
    }

    @Override
    public void relayTick(Duration duration) {
        relayTicks.increment();
        relayTickDuration.record(duration);
    }

    void updateBacklog(Map<OutboxState, Long> depthByState, Duration oldestPendingAge) {
        Map<OutboxState, Long> snapshot = Map.copyOf(depthByState);
        if (snapshot.values().stream().anyMatch(depth -> depth < 0)
                || Objects.requireNonNull(oldestPendingAge, "oldestPendingAge").isNegative()) {
            throw new IllegalArgumentException("Outbox backlog values must be non-negative");
        }
        backlog.set(new BacklogSnapshot(snapshot, oldestPendingAge));
    }

    private static Counter counter(MeterRegistry registry, String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }

    private record BacklogSnapshot(
            Map<OutboxState, Long> depthByState, Duration oldestPendingAge) {}

    enum OutboxState {
        PENDING,
        CLAIMED,
        PUBLISHED,
        FAILED
    }
}
