package org.meldtech.platform.platform.infra.observability;

import java.util.Objects;
import org.meldtech.platform.shared.kernel.observability.RequestTelemetry;
import org.reactivestreams.Publisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!api & !worker & !pindist")
class NoOpRequestTelemetryConfiguration {

    @Bean
    RequestTelemetry requestTelemetry() {
        return new RequestTelemetry() {
            @Override
            public <T> Publisher<T> observe(RequestMetadata metadata, Publisher<T> request) {
                Objects.requireNonNull(metadata, "metadata");
                return Objects.requireNonNull(request, "request");
            }
        };
    }
}
