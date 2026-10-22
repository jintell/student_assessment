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
import java.util.function.LongSupplier;

final class MicrometerOutboxTelemetry implements OutboxTelemetry, OutboxBacklogTelemetry {

    private final Counter writerAppended;
    private final Counter relayPublished;
    private final Map<PublicationFailureReason, Counter> publishFailures;
    private final Counter staleClaimsReclaimed;
    private final Counter eventFailed;
    private final Counter consumerDuplicates;
    private final Counter relayTicks;
    private final Timer relayTickDuration;
    private final Counter collectionFailures;
    private final long maximumSampleAgeNanos;
    private final LongSupplier monotonicTime;
    private final AtomicReference<BacklogSnapshot> backlog = new AtomicReference<>();

    MicrometerOutboxTelemetry(
            MeterRegistry registry, Duration maximumSampleAge, LongSupplier monotonicTime) {
        Objects.requireNonNull(registry, "registry");
        if (maximumSampleAge.isNegative() || maximumSampleAge.isZero()) {
            throw new IllegalArgumentException("Maximum sample age must be positive");
        }
        this.maximumSampleAgeNanos = maximumSampleAge.toNanos();
        this.monotonicTime = Objects.requireNonNull(monotonicTime, "monotonicTime");
        collectionFailures =
                counter(
                        registry,
                        "outbox.backlog.collection.failure",
                        "Failed, timed out or cancelled backlog collections");
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
                                return !isFresh(current)
                                        ? Double.NaN
                                        : depth(Objects.requireNonNull(current).values(), state);
                            })
                    .description(
                            "Global outbox row count by state; unknown before collection or after expiry")
                    .tag("state", state.name())
                    .register(registry);
        }
        Gauge.builder(
                        "outbox.oldest.pending.age.seconds",
                        backlog,
                        snapshot -> {
                            BacklogSnapshot current = snapshot.get();
                            return !isFresh(current)
                                    ? Double.NaN
                                    : seconds(
                                            Objects.requireNonNull(current)
                                                    .values()
                                                    .oldestPendingAge());
                        })
                .description(
                        "Database age of oldest pending row at observation; unknown before collection or after expiry")
                .register(registry);
        Gauge.builder(
                        "outbox.backlog.sample.fresh",
                        backlog,
                        snapshot -> isFresh(snapshot.get()) ? 1 : 0)
                .description("One only while this worker has a fresh complete backlog snapshot")
                .register(registry);
        Gauge.builder(
                        "outbox.backlog.last.success.timestamp.seconds",
                        backlog,
                        snapshot -> {
                            BacklogSnapshot current = snapshot.get();
                            return current == null
                                    ? Double.NaN
                                    : current.values().observedAt().toEpochMilli() / 1000.0;
                        })
                .description("Database observation time of last successful backlog collection")
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

    @Override
    public void updateBacklog(OutboxBacklogSnapshot snapshot, long collectionStartedNanos) {
        backlog.set(
                new BacklogSnapshot(
                        Objects.requireNonNull(snapshot, "snapshot"), collectionStartedNanos));
    }

    @Override
    public void backlogCollectionFailed() {
        collectionFailures.increment();
    }

    private boolean isFresh(@org.jspecify.annotations.Nullable BacklogSnapshot snapshot) {
        return snapshot != null
                && monotonicTime.getAsLong() - snapshot.collectionStartedNanos()
                        < maximumSampleAgeNanos;
    }

    private static double depth(OutboxBacklogSnapshot snapshot, OutboxState state) {
        return switch (state) {
            case PENDING -> snapshot.pending();
            case CLAIMED -> snapshot.claimed();
            case PUBLISHED -> snapshot.published();
            case FAILED -> snapshot.failed();
        };
    }

    private static double seconds(Duration duration) {
        return duration.toSeconds() + duration.toNanosPart() / 1_000_000_000.0;
    }

    private static Counter counter(MeterRegistry registry, String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }

    private record BacklogSnapshot(OutboxBacklogSnapshot values, long collectionStartedNanos) {}

    enum OutboxState {
        PENDING,
        CLAIMED,
        PUBLISHED,
        FAILED
    }
}
