package org.meldtech.platform.platform.infra.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.Objects;
import org.meldtech.platform.shared.kernel.observability.RequestTelemetry;
import org.meldtech.platform.shared.kernel.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile({"api", "worker", "pindist"})
@EnableConfigurationProperties(ObservabilityProperties.class)
class OpenTelemetryTracerConfiguration {

    static final String INSTRUMENTATION_SCOPE = "org.meldtech.platform";
    static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
    static final AttributeKey<String> ENVIRONMENT =
            AttributeKey.stringKey("deployment.environment.name");
    static final AttributeKey<String> RUNTIME_ROLE = AttributeKey.stringKey("service.runtime.role");

    @Bean(destroyMethod = "close")
    SdkTracerProvider observabilityTracerProvider(
            ObservabilityProperties properties, Sampler observabilitySampler) {
        return tracerProvider(properties.resource(), observabilitySampler);
    }

    @Bean
    Sampler observabilitySampler(ObservabilityProperties properties) {
        return Sampler.parentBased(new HybridRouteSampler(properties.sampling()));
    }

    @Bean
    OpenTelemetry openTelemetry(SdkTracerProvider provider) {
        return OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    }

    @Bean
    PlatformTracer platformTracer(OpenTelemetry openTelemetry) {
        Tracer tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
        return new PlatformTracer(tracer);
    }

    @Bean
    RequestTelemetry requestTelemetry(PlatformTracer tracer, Clock clock) {
        return new OpenTelemetryRequestTelemetry(tracer, clock);
    }

    @Bean
    HttpServerTracingWebFilter httpServerTracingWebFilter(PlatformTracer tracer) {
        return new HttpServerTracingWebFilter(tracer);
    }

    @Bean
    TraceContextContinuation traceContextContinuation(
            OpenTelemetry openTelemetry, Clock clock, ObservabilityProperties properties) {
        return new TraceContextContinuation(
                openTelemetry, clock, properties.trace().maxContinuationAge());
    }

    static SdkTracerProvider tracerProvider(
            ObservabilityProperties.Resource configuration, Sampler sampler) {
        return SdkTracerProvider.builder()
                .setResource(resource(configuration))
                .setSampler(Objects.requireNonNull(sampler, "sampler"))
                .build();
    }

    static Resource resource(ObservabilityProperties.Resource configuration) {
        Objects.requireNonNull(configuration, "configuration");
        return Resource.create(
                Attributes.builder()
                        .put(SERVICE_NAME, requireText(configuration.serviceName(), "serviceName"))
                        .put(ENVIRONMENT, requireText(configuration.environment(), "environment"))
                        .put(RUNTIME_ROLE, requireText(configuration.role(), "role"))
                        .build());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Observability resource " + name + " is required");
        }
        return value;
    }
}
