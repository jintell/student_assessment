package org.meldtech.platform.migration.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.DeployFreezeRefusalReason;

class MigrationMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MigrationMetrics metrics =
            new MigrationMetrics(registry, Set.of("delivery.answer"));

    @Test
    void publishesTheFiveContractedMetersWithBoundedTags() {
        metrics.recordDuration("delivery", MigrationClassification.EXPAND, Duration.ofSeconds(2));
        metrics.recordLockHeld(
                "delivery",
                "delivery.answer",
                PostgreSqlLockMode.ACCESS_EXCLUSIVE,
                Duration.ofMillis(125));
        metrics.recordOutcome(MigrationClassification.EXPAND, MigrationOutcome.SUCCESS);
        metrics.recordForbiddenOperation(
                MigrationClassification.EXPAND, MigrationRejectionCode.NON_CONCURRENT_INDEX);
        metrics.record(DeployFreezeRefusalReason.SESSION_OPEN);

        assertEquals(
                2.0,
                registry.get("migration_duration_seconds")
                        .timer()
                        .totalTime(java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(
                0.125,
                registry.get("migration_lock_held_seconds").summary().totalAmount(),
                0.000_001);
        assertEquals(1.0, registry.get("migration_outcome_total").counter().count());
        assertEquals(1.0, registry.get("migration_forbidden_operation_total").counter().count());
        assertEquals(1.0, registry.get("deploy_freeze_refusal_total").counter().count());
    }

    @Test
    void rejectsUnregisteredRelationLabels() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        metrics.recordLockHeld(
                                "delivery",
                                "delivery.unbounded_user_input",
                                PostgreSqlLockMode.ACCESS_EXCLUSIVE,
                                Duration.ofMillis(1)));
    }
}
