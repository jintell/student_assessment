package org.meldtech.platform.platform.infra.observability;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

final class ObservabilityHealthIndicator implements HealthIndicator {

    private static final String DEGRADED = "DEGRADED";

    private final ObservabilityHealthMetrics metrics;

    ObservabilityHealthIndicator(ObservabilityHealthMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public Health health() {
        Set<String> degradedSignals =
                metrics.degradedSignals().stream()
                        .map(signal -> signal.name().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        if (degradedSignals.isEmpty()) {
            return Health.up().withDetail("export", "healthy-or-not-yet-attempted").build();
        }
        return Health.status(DEGRADED)
                .withDetail("export", "failure-observed")
                .withDetail("signals", degradedSignals)
                .build();
    }
}
