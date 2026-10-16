package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.meldtech.platform.platform.api.SweepLockLostException;
import reactor.core.publisher.SignalType;

class SweepTelemetryTest {
    @Test
    void recordsShutdownPhaseDurationAndAbandonedUnitsOnce() {
        var clock = new MockClock();
        var meters = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        try {
            var shutdown = new SweepTelemetry(meters, () -> Instant.EPOCH).startShutdown(2);
            clock.add(Duration.ofSeconds(3));
            shutdown.finish();
            shutdown.finish();
            assertThat(
                            meters.get("shutdown_phase_duration_seconds")
                                    .tag("phase", "sweep")
                                    .timer()
                                    .totalTime(java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(3);
            assertThat(
                            meters.get("shutdown_abandoned_units_total")
                                    .tag("phase", "sweep")
                                    .counter()
                                    .count())
                    .isEqualTo(2);
        } finally {
            meters.close();
        }
    }

    @ParameterizedTest
    @EnumSource(SweepTelemetry.Outcome.class)
    void recordsBoundedOutcomesDurationAndSuccessfulTimestamp(SweepTelemetry.Outcome outcome) {
        var clock = new MockClock();
        var meters = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        try {
            var telemetry = new SweepTelemetry(meters, () -> Instant.ofEpochSecond(123));
            var tick = telemetry.start("reference");
            if (outcome != SweepTelemetry.Outcome.SKIPPED_LOCK_NOT_ACQUIRED) {
                tick.acquired();
            }
            switch (outcome) {
                case SKIPPED_LOCK_LOST -> {
                    var cleanupFailure = new RuntimeException("cleanup failed");
                    cleanupFailure.addSuppressed(new SweepLockLostException());
                    tick.failed(cleanupFailure);
                }
                case TIMEOUT -> tick.failed(new TimeoutException());
                case FAILED -> tick.failed(new IllegalStateException());
                case SUCCESS -> tick.batch(3);
                default -> {}
            }
            clock.add(Duration.ofSeconds(2));
            tick.finish(SignalType.ON_COMPLETE, outcome == SweepTelemetry.Outcome.INTERRUPTED);
            tick.finish(SignalType.ON_COMPLETE, false);
            assertThat(
                            meters.get("sweep_tick_total")
                                    .tag(
                                            "outcome",
                                            outcome.name().toLowerCase(java.util.Locale.ROOT))
                                    .counter()
                                    .count())
                    .isEqualTo(1);
            assertThat(
                            meters.get("sweep_duration_seconds")
                                    .timer()
                                    .totalTime(java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(2);
            assertThat(meters.get("sweep_last_success_timestamp_seconds").gauge().value())
                    .isEqualTo(outcome == SweepTelemetry.Outcome.SUCCESS ? 123 : 0);
            assertThat(
                            meters.get("sweep_lock_acquire_total")
                                    .tag(
                                            "result",
                                            outcome
                                                            == SweepTelemetry.Outcome
                                                                    .SKIPPED_LOCK_NOT_ACQUIRED
                                                    ? "not_acquired"
                                                    : "acquired")
                                    .counter()
                                    .count())
                    .isEqualTo(1);
            if (outcome == SweepTelemetry.Outcome.SUCCESS) {
                assertThat(meters.get("sweep_batch_rows_total").counter().count()).isEqualTo(3);
            }
            meters.getMeters()
                    .forEach(
                            meter ->
                                    assertThat(meter.getId().getTags())
                                            .allMatch(
                                                    tag ->
                                                            Set.of("sweep", "outcome", "result")
                                                                    .contains(tag.getKey())));
        } finally {
            meters.close();
        }
    }
}
