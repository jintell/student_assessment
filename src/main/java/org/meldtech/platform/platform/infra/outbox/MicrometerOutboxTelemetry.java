package org.meldtech.platform.platform.infra.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

final class MicrometerOutboxTelemetry implements OutboxTelemetry {

    private final Counter writerAppended;
    private final Counter relayPublished;
    private final Map<PublicationFailureReason, Counter> publishFailures;
    private final Counter staleClaimsReclaimed;
    private final Counter eventFailed;
    private final Counter consumerDuplicates;
    private final Counter relayTicks;
    private final Timer relayTickDuration;
    private final Map<OutboxState, AtomicLong> backlogDepth = new EnumMap<>(OutboxState.class);
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

    MicrometerOutboxTelemetry(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        writerAppended = registry.counter("outbox.writer.appended");
        relayPublished = registry.counter("outbox.relay.published");
        staleClaimsReclaimed = registry.counter("outbox.stale.claim.reclaimed");
        eventFailed = registry.counter("outbox.event.failed");
        consumerDuplicates = registry.counter("outbox.consumer.duplicate");
        relayTicks = registry.counter("outbox.relay.tick");
        relayTickDuration = registry.timer("outbox.relay.tick.duration");
        publishFailures = new EnumMap<>(PublicationFailureReason.class);
        for (PublicationFailureReason reason : PublicationFailureReason.values()) {
            publishFailures.put(
                    reason,
                    Counter.builder("outbox.relay.publish.failure")
                            .tag("reason", reason.name())
                            .register(registry));
        }
        for (OutboxState state : OutboxState.values()) {
            AtomicLong depth = new AtomicLong();
            backlogDepth.put(state, depth);
            Gauge.builder("outbox.backlog.depth", depth, AtomicLong::doubleValue)
                    .tag("state", state.name())
                    .register(registry);
        }
        Gauge.builder(
                        "outbox.oldest.pending.age.seconds",
                        oldestPendingAgeSeconds,
                        AtomicLong::doubleValue)
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
        Objects.requireNonNull(depthByState, "depthByState");
        for (OutboxState state : OutboxState.values()) {
            Objects.requireNonNull(backlogDepth.get(state), "backlog gauge")
                    .set(depthByState.getOrDefault(state, 0L));
        }
        oldestPendingAgeSeconds.set(
                Math.max(
                        0L,
                        Objects.requireNonNull(oldestPendingAge, "oldestPendingAge").toSeconds()));
    }

    enum OutboxState {
        PENDING,
        CLAIMED,
        PUBLISHED,
        FAILED
    }
}
