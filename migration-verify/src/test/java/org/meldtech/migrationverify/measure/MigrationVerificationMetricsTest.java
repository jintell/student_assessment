package org.meldtech.migrationverify.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MigrationVerificationMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MigrationVerificationMetrics metrics = new MigrationVerificationMetrics(registry);

    @Test
    void publishesTheHarnessMeasurementWithoutChangingItsDefinition() {
        metrics.recordLockHeld(
                "delivery",
                new LockHoldMeasurement(1, "delivery.answer", "AccessExclusiveLock", 125));

        var summary =
                registry.get("migration_lock_held_seconds")
                        .tags(
                                "module",
                                "delivery",
                                "relation",
                                "delivery.answer",
                                "lock_mode",
                                "AccessExclusiveLock")
                        .summary();
        assertEquals(1, summary.count());
        assertEquals(0.125, summary.totalAmount(), 0.000_001);
    }

    @Test
    void rejectsLabelsOutsideTheBoundedVocabulary() {
        var unknownMode = new LockHoldMeasurement(1, "delivery.answer", "UnknownLock", 1);

        assertThrows(
                IllegalArgumentException.class,
                () -> metrics.recordLockHeld("delivery", unknownMode));
    }

    @Test
    void publishesOutcomeAndForbiddenOperationCounters() {
        metrics.recordOutcome("EXPAND", "STATIC_REFUSAL");
        metrics.recordForbiddenOperation("EXPAND", "NON_CONCURRENT_INDEX");

        assertEquals(
                1.0,
                registry.get("migration_outcome_total")
                        .tags("classification", "EXPAND", "outcome", "STATIC_REFUSAL")
                        .counter()
                        .count());
        assertEquals(
                1.0,
                registry.get("migration_forbidden_operation_total")
                        .tags("classification", "EXPAND", "rejection_code", "NON_CONCURRENT_INDEX")
                        .counter()
                        .count());
    }
}
