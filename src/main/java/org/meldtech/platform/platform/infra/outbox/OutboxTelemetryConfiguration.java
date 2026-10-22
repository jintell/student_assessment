package org.meldtech.platform.platform.infra.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.meldtech.platform.platform.api.SweepTransactions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableConfigurationProperties(OutboxBacklogProperties.class)
class OutboxTelemetryConfiguration {

    @Bean
    MicrometerOutboxTelemetry outboxTelemetry(
            MeterRegistry registry, OutboxBacklogProperties properties) {
        return new MicrometerOutboxTelemetry(
                registry, properties.maximumSampleAge(), System::nanoTime);
    }

    @Bean
    OutboxBacklogSweep outboxBacklogSweep(
            SweepTransactions transactions,
            MicrometerOutboxTelemetry telemetry,
            OutboxBacklogProperties properties) {
        return new OutboxBacklogSweep(
                transactions,
                new DatabaseOutboxBacklogReader(),
                telemetry,
                properties,
                System::nanoTime);
    }
}
