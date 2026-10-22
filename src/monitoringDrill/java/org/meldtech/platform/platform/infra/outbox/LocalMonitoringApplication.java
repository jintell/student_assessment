package org.meldtech.platform.platform.infra.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Instant;
import java.util.Arrays;
import org.meldtech.platform.migration.MigrationApplication;
import org.meldtech.platform.platform.infra.observability.DatabaseQueryTelemetry;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.CorrelationIdGenerator;
import org.meldtech.platform.shared.kernel.time.Clock;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class LocalMonitoringApplication {
    public static void main(String[] arguments) throws Exception {
        if (Arrays.asList(arguments).contains("--receiver")) {
            LocalNotificationReceiver.start();
            return;
        }
        if (MigrationApplication.isRequested(arguments)) {
            MigrationApplication.run(arguments);
            return;
        }
        new SpringApplicationBuilder(
                        LocalMonitoringApplication.class,
                        OutboxTelemetryConfiguration.class,
                        LocalRelay.class,
                        Class.forName(
                                "org.meldtech.platform.platform.infra.persistence.WorkloadConnectionPoolConfiguration"),
                        Class.forName(
                                "org.meldtech.platform.platform.infra.persistence.DefaultSweepTransactions"),
                        Class.forName(
                                "org.meldtech.platform.platform.infra.runtime.SessionSweepLock"),
                        Class.forName(
                                "org.meldtech.platform.platform.infra.runtime.SweepRegistryLoader"),
                        Class.forName("org.meldtech.platform.platform.infra.runtime.SweepRunner"),
                        Class.forName(
                                "org.meldtech.platform.platform.infra.runtime.SweepTelemetry"),
                        Class.forName(
                                "org.meldtech.platform.platform.infra.runtime.WorkerWebServerConfiguration"),
                        Class.forName(
                                "org.meldtech.platform.shared.infra.web.RequestContextPropagation"))
                .web(WebApplicationType.REACTIVE)
                .run(arguments);
    }

    @Bean
    Clock clock() {
        return Instant::now;
    }

    @Bean
    OpenTelemetry openTelemetry() {
        return OpenTelemetry.noop();
    }

    @Bean
    DatabaseQueryTelemetry databaseQueryTelemetry() {
        return DatabaseQueryTelemetry.NOOP;
    }

    @Bean
    CorrelationIdGenerator correlationIds() {
        return () -> new CorrelationId("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    }

    @Bean
    LocalRelayControl localRelayControl(MeterRegistry meters) {
        return new LocalRelayControl(meters);
    }

    @Bean
    SecurityWebFilterChain localDrillSecurity(ServerHttpSecurity security) {
        return security.authorizeExchange(exchanges -> exchanges.anyExchange().authenticated())
                .httpBasic(Customizer.withDefaults())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .build();
    }
}
