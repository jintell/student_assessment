package org.meldtech.platform.platform.infra.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import org.meldtech.platform.platform.api.RuntimeRole;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile({"api", "worker", "pindist"})
class RuntimeTelemetryConfiguration {
    @Bean
    MeterRegistryCustomizer<MeterRegistry> runtimeRoleMetrics(
            RuntimeRole role, ObservabilityProperties properties) {
        if (!role.profile().equals(properties.resource().role())) {
            throw new IllegalStateException("RUNTIME_TELEMETRY_ROLE_MISMATCH");
        }
        return registry ->
                registry.config()
                        .meterFilter(
                                new MeterFilter() {
                                    @Override
                                    public Meter.Id map(Meter.Id id) {
                                        return id.withTag(Tag.of("runtime_role", role.profile()));
                                    }
                                });
    }
}
