package org.meldtech.platform.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.migration.telemetry.MigrationClassification;
import org.meldtech.platform.migration.telemetry.MigrationMetrics;

class MigrationTelemetryTest {

    @Test
    void recordsEveryModuleDurationWithTheReleaseClassification() {
        var registry = new SimpleMeterRegistry();
        var clock = new AtomicLong();

        MigrationApplication.runMigrations(
                MigrationClassification.EXPAND,
                MigrationMetrics.create(registry),
                ignored -> clock.addAndGet(2_000_000_000L),
                clock::get);

        for (MigrationSchema schema : MigrationSchema.values()) {
            var timer =
                    registry.get("migration_duration_seconds")
                            .tags("module", schema.schemaName(), "classification", "EXPAND")
                            .timer();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(2.0);
        }
        assertThat(
                        registry.get("migration_outcome_total")
                                .tags("classification", "EXPAND", "outcome", "SUCCESS")
                                .counter()
                                .count())
                .isEqualTo(1.0);
    }

    @Test
    void recordsExecutionFailureBeforePropagatingIt() {
        var registry = new SimpleMeterRegistry();

        assertThatThrownBy(
                        () ->
                                MigrationApplication.runMigrations(
                                        MigrationClassification.CONTRACT,
                                        MigrationMetrics.create(registry),
                                        ignored -> {
                                            throw new IllegalStateException("migration failed");
                                        },
                                        System::nanoTime))
                .isInstanceOf(IllegalStateException.class);

        assertThat(
                        registry.get("migration_outcome_total")
                                .tags("classification", "CONTRACT", "outcome", "EXECUTION_FAILED")
                                .counter()
                                .count())
                .isEqualTo(1.0);
    }
}
