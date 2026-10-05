package org.meldtech.platform.platform.infra.observability;

import io.opentelemetry.sdk.common.CompletableResultCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.meldtech.platform.shared.kernel.time.Clock;

final class BoundedExportQueue<T> {

    private final LinkedBlockingDeque<Entry<T>> queue;
    private final int batchSize;
    private final Duration itemMaximumAge;
    private final Duration exportTimeout;
    private final Clock clock;
    private final ExportOperation<T> exporter;
    private final Runnable shutdown;
    private final TelemetryHealth health;
    private final ObservabilityHealthMetrics.Signal signal;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong dropped = new AtomicLong();
    private final Thread worker;

    BoundedExportQueue(
            String signal,
            ObservabilityProperties.Queue configuration,
            Duration exportTimeout,
            Clock clock,
            ExportOperation<T> exporter,
            Runnable shutdown) {
        this(
                signal,
                ObservabilityHealthMetrics.Signal.TRACE,
                configuration,
                exportTimeout,
                clock,
                exporter,
                shutdown,
                TelemetryHealth.NOOP);
    }

    BoundedExportQueue(
            String workerName,
            ObservabilityHealthMetrics.Signal signal,
            ObservabilityProperties.Queue configuration,
            Duration exportTimeout,
            Clock clock,
            ExportOperation<T> exporter,
            Runnable shutdown,
            TelemetryHealth health) {
        Objects.requireNonNull(workerName, "workerName");
        this.signal = Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(configuration, "configuration");
        this.exportTimeout = requirePositive(exportTimeout, "export timeout");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.exporter = Objects.requireNonNull(exporter, "exporter");
        this.shutdown = Objects.requireNonNull(shutdown, "shutdown");
        this.health = Objects.requireNonNull(health, "health");
        int capacity = requirePositive(configuration.capacity(), "queue capacity");
        batchSize = requireRange(configuration.batchSize(), 1, capacity, "queue batch size");
        itemMaximumAge = requirePositive(configuration.itemMaxAge(), "queue item maximum age");
        queue = new LinkedBlockingDeque<>(capacity);
        worker =
                Thread.ofPlatform()
                        .daemon(true)
                        .name("telemetry-" + workerName + "-exporter")
                        .start(this::exportLoop);
    }

    void offer(T item) {
        Entry<T> entry = new Entry<>(Objects.requireNonNull(item, "item"), clock.now());
        while (!queue.offerLast(entry)) {
            if (queue.pollFirst() != null) {
                dropped.incrementAndGet();
                health.dropped(signal, ObservabilityHealthMetrics.DropReason.OVERFLOW, 1);
            }
        }
        health.queueDepth(signal, queue.size());
    }

    CompletableResultCode forceFlush() {
        return queue.isEmpty()
                ? CompletableResultCode.ofSuccess()
                : CompletableResultCode.ofFailure();
    }

    CompletableResultCode shutdown() {
        if (running.compareAndSet(true, false)) {
            worker.interrupt();
            queue.clear();
            health.queueDepth(signal, 0);
            shutdown.run();
        }
        return CompletableResultCode.ofSuccess();
    }

    int size() {
        return queue.size();
    }

    long droppedCount() {
        return dropped.get();
    }

    private void exportLoop() {
        while (running.get()) {
            try {
                Entry<T> first = queue.takeFirst();
                List<Entry<T>> entries = new ArrayList<>(batchSize);
                entries.add(first);
                queue.drainTo(entries, batchSize - 1);
                health.queueDepth(signal, queue.size());
                export(entries);
            } catch (InterruptedException interrupted) {
                if (!running.get()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (RuntimeException ignored) {
                // Export failure degrades telemetry only; the worker continues with the next batch.
            }
        }
    }

    private void export(List<Entry<T>> entries) {
        Instant now = clock.now();
        List<T> batch = new ArrayList<>(entries.size());
        for (Entry<T> entry : entries) {
            if (Duration.between(entry.enqueuedAt(), now).compareTo(itemMaximumAge) > 0) {
                dropped.incrementAndGet();
                health.dropped(signal, ObservabilityHealthMetrics.DropReason.EXPIRY, 1);
            } else {
                batch.add(entry.item());
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        health.exportAttempt(signal);
        try {
            CompletableResultCode result =
                    exporter.export(List.copyOf(batch))
                            .join(exportTimeout.toNanos(), TimeUnit.NANOSECONDS);
            if (result.isSuccess()) {
                health.exportSuccess(signal);
                return;
            }
            if (!result.isDone()) {
                health.exportTimeout(signal);
            }
            health.dropped(
                    signal, ObservabilityHealthMetrics.DropReason.EXPORT_FAILURE, batch.size());
        } catch (RuntimeException ignored) {
            health.dropped(
                    signal, ObservabilityHealthMetrics.DropReason.EXPORT_FAILURE, batch.size());
        }
    }

    private static int requirePositive(Integer value, String name) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int requireRange(Integer value, int minimum, int maximum, String name) {
        if (value == null || value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    @FunctionalInterface
    interface ExportOperation<T> {

        CompletableResultCode export(Collection<T> batch);
    }

    private record Entry<T>(T item, Instant enqueuedAt) {}
}
