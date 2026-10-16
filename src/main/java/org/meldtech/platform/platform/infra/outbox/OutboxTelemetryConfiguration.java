package org.meldtech.platform.platform.infra.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("worker")
class OutboxTelemetryConfiguration {

    @Bean
    MicrometerOutboxTelemetry outboxTelemetry(MeterRegistry registry) {
        return new MicrometerOutboxTelemetry(registry);
    }
}
