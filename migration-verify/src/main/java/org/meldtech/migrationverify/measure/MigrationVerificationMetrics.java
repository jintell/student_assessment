package org.meldtech.migrationverify.measure;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.ipc.http.HttpUrlConnectionSender;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpHttpMetricsSender;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

final class MigrationVerificationMetrics implements AutoCloseable {

    private static final Pattern MODULE = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern RELATION = Pattern.compile("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*");
    private static final Set<String> LOCK_MODES =
            Set.of(
                    "AccessShareLock",
                    "RowShareLock",
                    "RowExclusiveLock",
                    "ShareUpdateExclusiveLock",
                    "ShareLock",
                    "ShareRowExclusiveLock",
                    "ExclusiveLock",
                    "AccessExclusiveLock");
    private static final Set<String> CLASSIFICATIONS = Set.of("EXPAND", "MIGRATE", "CONTRACT");
    private static final Set<String> OUTCOMES =
            Set.of(
                    "SUCCESS",
                    "STATIC_REFUSAL",
                    "LOCK_THRESHOLD_FAILED",
                    "EXECUTION_FAILED",
                    "COMPATIBILITY_FAILED",
                    "CANCELLED");
    private static final Pattern REJECTION_CODE = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(5);

    private final MeterRegistry registry;

    MigrationVerificationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    static MigrationVerificationMetrics open() {
        if (!hasOtlpEndpoint()) {
            return new MigrationVerificationMetrics(new SimpleMeterRegistry());
        }
        OtlpConfig config = key -> null;
        OtlpMeterRegistry registry =
                OtlpMeterRegistry.builder(config)
                        .clock(Clock.SYSTEM)
                        .metricsSender(
                                new OtlpHttpMetricsSender(
                                        new HttpUrlConnectionSender(
                                                EXPORT_TIMEOUT, EXPORT_TIMEOUT)))
                        .build();
        return new MigrationVerificationMetrics(registry);
    }

    void recordLockHeld(String module, LockHoldMeasurement hold) {
        requireMatch(MODULE, module, "module");
        requireMatch(RELATION, hold.relation(), "relation");
        if (!LOCK_MODES.contains(hold.lockMode())) {
            throw new IllegalArgumentException("Unknown PostgreSQL lock mode: " + hold.lockMode());
        }
        DistributionSummary.builder("migration_lock_held_seconds")
                .description("Maximum continuous granted relation-lock hold")
                .baseUnit("seconds")
                .tag("module", module)
                .tag("relation", hold.relation())
                .tag("lock_mode", hold.lockMode())
                .serviceLevelObjectives(0.1, 0.25, 2.0)
                .register(registry)
                .record(hold.measuredHoldMillis() / 1_000.0);
    }

    void recordOutcome(String classification, String outcome) {
        requireMember(CLASSIFICATIONS, classification, "classification");
        requireMember(OUTCOMES, outcome, "outcome");
        counter("migration_outcome_total", "classification", classification, "outcome", outcome)
                .increment();
    }

    void recordForbiddenOperation(String classification, String rejectionCode) {
        requireMember(CLASSIFICATIONS, classification, "classification");
        requireMatch(REJECTION_CODE, rejectionCode, "rejection code");
        counter(
                        "migration_forbidden_operation_total",
                        "classification",
                        classification,
                        "rejection_code",
                        rejectionCode)
                .increment();
    }

    @Override
    public void close() {
        registry.close();
    }

    private static boolean hasOtlpEndpoint() {
        return System.getenv("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT") != null
                || System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") != null;
    }

    private static void requireMatch(Pattern pattern, String value, String label) {
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid migration metric " + label + ": " + value);
        }
    }

    private static void requireMember(Set<String> values, String value, String label) {
        if (!values.contains(value)) {
            throw new IllegalArgumentException("Unknown migration metric " + label + ": " + value);
        }
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }
}
