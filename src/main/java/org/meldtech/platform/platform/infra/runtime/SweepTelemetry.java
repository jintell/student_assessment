package org.meldtech.platform.platform.infra.runtime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.meldtech.platform.platform.api.RoleScoped;
import org.meldtech.platform.platform.api.SweepLockLostException;
import org.meldtech.platform.shared.kernel.time.Clock;
import org.springframework.stereotype.Component;
import reactor.core.publisher.SignalType;

@Component
@RoleScoped("worker")
final class SweepTelemetry {
    enum Outcome {
        SUCCESS,
        SKIPPED_LOCK_NOT_ACQUIRED,
        SKIPPED_LOCK_LOST,
        TIMEOUT,
        FAILED,
        INTERRUPTED
    }

    enum LockResult {
        ACQUIRED,
        NOT_ACQUIRED
    }

    private final MeterRegistry meters;
    private final Clock clock;
    private final Map<String, AtomicLong> lastSuccess = new HashMap<>();

    SweepTelemetry(MeterRegistry meters, Clock clock) {
        this.meters = meters;
        this.clock = clock;
    }

    synchronized Tick start(String sweep) {
        if (!lastSuccess.containsKey(sweep)) {
            if (lastSuccess.size() >= 128 || !sweep.matches("[a-z][a-z0-9-]{0,95}")) {
                throw new IllegalArgumentException("Sweep telemetry catalogue exceeded");
            }
            var timestamp = new AtomicLong();
            lastSuccess.put(sweep, timestamp);
            Gauge.builder(
                            "sweep_last_success_timestamp_seconds",
                            timestamp,
                            AtomicLong::doubleValue)
                    .tag(SweepLogPolicy.SWEEP, sweep)
                    .register(meters);
        }
        return new Tick(sweep, java.util.Objects.requireNonNull(lastSuccess.get(sweep)));
    }

    Shutdown startShutdown(long abandonedUnits) {
        meters.counter("shutdown_abandoned_units_total", "phase", "sweep")
                .increment((double) abandonedUnits);
        return new Shutdown();
    }

    final class Shutdown {
        private final long started = meters.config().clock().monotonicTime();
        private final AtomicBoolean recorded = new AtomicBoolean();

        void finish() {
            if (recorded.compareAndSet(false, true)) {
                meters.timer("shutdown_phase_duration_seconds", "phase", "sweep")
                        .record(
                                meters.config().clock().monotonicTime() - started,
                                TimeUnit.NANOSECONDS);
            }
        }
    }

    final class Tick {
        private final String sweep;
        private final AtomicLong timestamp;
        private final long started = meters.config().clock().monotonicTime();
        private final AtomicBoolean recorded = new AtomicBoolean();
        private boolean lockAcquired;
        private Outcome outcome = Outcome.SKIPPED_LOCK_NOT_ACQUIRED;

        private Tick(String sweep, AtomicLong timestamp) {
            this.sweep = sweep;
            this.timestamp = timestamp;
        }

        synchronized void acquired() {
            lockAcquired = true;
            outcome = Outcome.SUCCESS;
        }

        void batch(int count) {
            meters.counter("sweep_batch_rows_total", SweepLogPolicy.SWEEP, sweep).increment(count);
        }

        synchronized void failed(Throwable failure) {
            outcome = Outcome.FAILED;
            if (contains(failure, SweepLockLostException.class)) {
                outcome = Outcome.SKIPPED_LOCK_LOST;
            } else if (contains(failure, TimeoutException.class)) {
                outcome = Outcome.TIMEOUT;
            }
        }

        private boolean contains(Throwable failure, Class<? extends Throwable> type) {
            Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            return contains(failure, type, visited);
        }

        private boolean contains(
                Throwable failure, Class<? extends Throwable> type, Set<Throwable> visited) {
            if (failure == null || !visited.add(failure)) {
                return false;
            }
            Throwable cause = failure.getCause();
            if (type.isInstance(failure) || (cause != null && contains(cause, type, visited))) {
                return true;
            }
            for (Throwable suppressed : failure.getSuppressed()) {
                if (contains(suppressed, type, visited)) {
                    return true;
                }
            }
            return false;
        }

        synchronized void finish(SignalType signal, boolean interrupted) {
            if (!recorded.compareAndSet(false, true)) {
                return;
            }
            if (signal == SignalType.CANCEL || (interrupted && outcome == Outcome.SUCCESS)) {
                outcome = Outcome.INTERRUPTED;
            }
            meters.counter(
                            "sweep_tick_total",
                            SweepLogPolicy.SWEEP,
                            sweep,
                            SweepLogPolicy.OUTCOME,
                            tag(outcome))
                    .increment();
            meters.timer(
                            "sweep_duration_seconds",
                            SweepLogPolicy.SWEEP,
                            sweep,
                            SweepLogPolicy.OUTCOME,
                            tag(outcome))
                    .record(
                            meters.config().clock().monotonicTime() - started,
                            TimeUnit.NANOSECONDS);
            meters.counter(
                            "sweep_lock_acquire_total",
                            SweepLogPolicy.SWEEP,
                            sweep,
                            "result",
                            tag(lockAcquired ? LockResult.ACQUIRED : LockResult.NOT_ACQUIRED))
                    .increment();
            if (outcome == Outcome.SUCCESS) {
                timestamp.set(clock.now().getEpochSecond());
            }
        }

        private String tag(Enum<?> value) {
            return value.name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
