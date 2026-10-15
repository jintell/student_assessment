package org.meldtech.platform.platform.infra.runtime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
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
            Gauge.builder("sweep.last.success.timestamp", timestamp, AtomicLong::doubleValue)
                    .tag(SweepLogPolicy.SWEEP, sweep)
                    .register(meters);
        }
        return new Tick(sweep, java.util.Objects.requireNonNull(lastSuccess.get(sweep)));
    }

    final class Tick {
        private final String sweep;
        private final AtomicLong timestamp;
        private final long started = meters.config().clock().monotonicTime();
        private final AtomicBoolean recorded = new AtomicBoolean();
        private Outcome outcome = Outcome.SKIPPED_LOCK_NOT_ACQUIRED;

        private Tick(String sweep, AtomicLong timestamp) {
            this.sweep = sweep;
            this.timestamp = timestamp;
        }

        synchronized void acquired() {
            outcome = Outcome.SUCCESS;
        }

        void batch(int count) {
            meters.summary("sweep.batch.size", SweepLogPolicy.SWEEP, sweep).record(count);
        }

        synchronized void failed(Throwable failure) {
            outcome = Outcome.FAILED;
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SweepLockLostException) {
                    outcome = Outcome.SKIPPED_LOCK_LOST;
                    break;
                }
                if (cause instanceof TimeoutException) {
                    outcome = Outcome.TIMEOUT;
                    break;
                }
            }
        }

        synchronized void finish(SignalType signal, boolean interrupted) {
            if (!recorded.compareAndSet(false, true)) {
                return;
            }
            if (signal == SignalType.CANCEL || (interrupted && outcome == Outcome.SUCCESS)) {
                outcome = Outcome.INTERRUPTED;
            }
            meters.counter(
                            "sweep.tick.outcome",
                            SweepLogPolicy.SWEEP,
                            sweep,
                            SweepLogPolicy.OUTCOME,
                            outcome.name())
                    .increment();
            meters.timer(
                            "sweep.tick.duration",
                            SweepLogPolicy.SWEEP,
                            sweep,
                            SweepLogPolicy.OUTCOME,
                            outcome.name())
                    .record(
                            meters.config().clock().monotonicTime() - started,
                            TimeUnit.NANOSECONDS);
            if (outcome == Outcome.SUCCESS) {
                timestamp.set(clock.now().getEpochSecond());
            }
        }
    }
}
