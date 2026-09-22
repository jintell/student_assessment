package org.meldtech.platform.migration.telemetry;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.ipc.http.HttpUrlConnectionSender;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpHttpMetricsSender;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import java.time.Duration;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;

public final class MigrationOtlpRegistry implements AutoCloseable {

    private static final String EXPORT_TIMEOUT = "cbt.migration.telemetry.export-timeout";

    private final OtlpMeterRegistry registry;
    private final MigrationMetrics metrics;

    private MigrationOtlpRegistry(OtlpMeterRegistry registry) {
        this.registry = registry;
        this.metrics = MigrationMetrics.create(registry);
    }

    public static MigrationOtlpRegistry open(Environment environment) {
        Duration exportTimeout =
                DurationStyle.detectAndParse(environment.getRequiredProperty(EXPORT_TIMEOUT));
        if (exportTimeout.isZero() || exportTimeout.isNegative()) {
            throw new IllegalStateException("Migration telemetry export timeout must be positive");
        }
        OtlpConfig config = key -> null;
        OtlpMeterRegistry registry =
                OtlpMeterRegistry.builder(config)
                        .clock(Clock.SYSTEM)
                        .metricsSender(
                                new OtlpHttpMetricsSender(
                                        new HttpUrlConnectionSender(exportTimeout, exportTimeout)))
                        .build();
        return new MigrationOtlpRegistry(registry);
    }

    public MigrationMetrics metrics() {
        return metrics;
    }

    @Override
    public void close() {
        registry.close();
    }
}
