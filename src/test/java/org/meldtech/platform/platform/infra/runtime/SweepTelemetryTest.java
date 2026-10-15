package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.meldtech.platform.platform.api.SweepLockLostException;
import reactor.core.publisher.SignalType;

class SweepTelemetryTest {
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
                            meters.get("sweep.tick.outcome")
                                    .tag("outcome", outcome.name())
                                    .counter()
                                    .count())
                    .isEqualTo(1);
            assertThat(
                            meters.get("sweep.tick.duration")
                                    .timer()
                                    .totalTime(java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(2);
            assertThat(meters.get("sweep.last.success.timestamp").gauge().value())
                    .isEqualTo(outcome == SweepTelemetry.Outcome.SUCCESS ? 123 : 0);
            if (outcome == SweepTelemetry.Outcome.SUCCESS) {
                assertThat(meters.get("sweep.batch.size").summary().totalAmount()).isEqualTo(3);
            }
            meters.getMeters()
                    .forEach(
                            meter ->
                                    assertThat(meter.getId().getTags())
                                            .allMatch(
                                                    tag ->
                                                            Set.of("sweep", "outcome")
                                                                    .contains(tag.getKey())));
        } finally {
            meters.close();
        }
    }
}
