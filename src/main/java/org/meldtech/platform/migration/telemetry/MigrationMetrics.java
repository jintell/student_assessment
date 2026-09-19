package org.meldtech.platform.migration.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.meldtech.platform.platform.api.DeployFreezeRefusalReason;
import org.meldtech.platform.platform.api.DeployFreezeRefusalRecorder;

public final class MigrationMetrics implements DeployFreezeRefusalRecorder {

    private static final Set<String> MODULES =
            Set.of(
                    "academic",
                    "audit",
                    "authoring",
                    "correction",
                    "delivery",
                    "examaccess",
                    "grading",
                    "iam",
                    "notification",
                    "outbox",
                    "people",
                    "platform",
                    "questionbank",
                    "result",
                    "tenancy");

    private final MeterRegistry registry;
    private final Set<String> knownRelations;

    public MigrationMetrics(MeterRegistry registry, Set<String> knownRelations) {
        this.registry = registry;
        this.knownRelations = Set.copyOf(knownRelations);
    }

    public void recordDuration(
            String module, MigrationClassification classification, Duration duration) {
        Timer.builder("migration_duration_seconds")
                .description("End-to-end duration of a module migration")
                .tag("module", module(module))
                .tag("classification", classification.name())
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    public void recordLockHeld(
            String module, String relation, PostgreSqlLockMode lockMode, Duration duration) {
        DistributionSummary.builder("migration_lock_held_seconds")
                .description("Maximum continuous granted relation-lock hold")
                .baseUnit("seconds")
                .tag("module", module(module))
                .tag("relation", relation(relation))
                .tag("lock_mode", lockMode.tag())
                .serviceLevelObjectives(0.1, 0.25, 2.0)
                .register(registry)
                .record(duration.toNanos() / 1_000_000_000.0);
    }

    public void recordOutcome(MigrationClassification classification, MigrationOutcome outcome) {
        counter(
                        "migration_outcome_total",
                        "classification",
                        classification.name(),
                        "outcome",
                        outcome.name())
                .increment();
    }

    public void recordForbiddenOperation(
            MigrationClassification classification, MigrationRejectionCode rejectionCode) {
        counter(
                        "migration_forbidden_operation_total",
                        "classification",
                        classification.name(),
                        "rejection_code",
                        rejectionCode.name())
                .increment();
    }

    @Override
    public void record(DeployFreezeRefusalReason reason) {
        counter("deploy_freeze_refusal_total", "reason", reason.name()).increment();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private static String module(String module) {
        String normalized = module.toLowerCase(Locale.ROOT);
        if (!MODULES.contains(normalized)) {
            throw new IllegalArgumentException("Unknown migration metric module: " + module);
        }
        return normalized;
    }

    private String relation(String relation) {
        if (!knownRelations.contains(relation)) {
            throw new IllegalArgumentException("Unknown migration metric relation: " + relation);
        }
        return relation;
    }
}
