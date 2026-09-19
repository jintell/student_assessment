package org.meldtech.platform.migration.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MigrationMetricsConfiguration {

    @Bean
    MigrationMetrics migrationMetrics(MeterRegistry registry) {
        return new MigrationMetrics(
                registry,
                Set.of(
                        "audit.audit_event",
                        "delivery.answer",
                        "delivery.answer_operation",
                        "delivery.attempt",
                        "platform.migration_fixture",
                        "platform.tenant_scope_probe"));
    }
}
