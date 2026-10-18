package org.meldtech.platform.audit.infra.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import org.meldtech.platform.audit.application.AuditIntegrityTelemetry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile({"api", "worker"})
class AuditTelemetryConfiguration {

    @Bean
    AuditIntegrityTelemetry auditIntegrityTelemetry(MeterRegistry registry) {
        return new MicrometerAuditIntegrityTelemetry(registry);
    }
}
