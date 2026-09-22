package org.meldtech.platform.migration.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MigrationMetricsConfiguration {

    @Bean
    MigrationMetrics migrationMetrics(MeterRegistry registry) {
        return MigrationMetrics.create(registry);
    }
}
