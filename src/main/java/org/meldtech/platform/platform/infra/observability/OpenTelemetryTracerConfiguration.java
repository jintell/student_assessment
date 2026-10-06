package org.meldtech.platform.platform.infra.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.meldtech.platform.shared.kernel.observability.RequestTelemetry;
import org.meldtech.platform.shared.kernel.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile({"api", "worker", "pindist"})
@EnableConfigurationProperties(ObservabilityProperties.class)
class OpenTelemetryTracerConfiguration {

    static final String INSTRUMENTATION_SCOPE = "org.meldtech.platform";
    static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
    static final AttributeKey<String> ENVIRONMENT =
            AttributeKey.stringKey("deployment.environment.name");
    static final AttributeKey<String> RUNTIME_ROLE = AttributeKey.stringKey("service.runtime.role");

    @Bean
    ObservabilityHealthMetrics observabilityHealthMetrics(
            MeterRegistry registry, ObservabilityProperties properties) {
        return new ObservabilityHealthMetrics(registry, properties);
    }

    @Bean
    ObservabilityHealthIndicator observabilityHealthIndicator(
            ObservabilityHealthMetrics healthMetrics) {
        return new ObservabilityHealthIndicator(healthMetrics);
    }

    @Bean
    ObservabilityConfigurationValidator observabilityConfigurationValidator(
            ObservabilityProperties properties, Environment environment) {
        ObservabilityConfigurationValidator validator =
                new ObservabilityConfigurationValidator(properties, environment);
        validator.validate();
        return validator;
    }

    @Bean(destroyMethod = "close")
    SdkTracerProvider observabilityTracerProvider(
            ObservabilityProperties properties,
            Sampler observabilitySampler,
            OtlpExportPipeline exportPipeline) {
        return tracerProvider(
                properties.resource(), observabilitySampler, exportPipeline.spanProcessor());
    }

    @Bean
    OtlpExportPipeline otlpExportPipeline(
            ObservabilityProperties properties,
            Clock clock,
            ObservabilityHealthMetrics healthMetrics,
            ObservabilityConfigurationValidator ignoredValidator) {
        return OtlpExportPipeline.create(properties, clock, healthMetrics);
    }

    @Bean(destroyMethod = "close")
    SdkMeterProvider observabilityMeterProvider(
            ObservabilityProperties properties, OtlpExportPipeline exportPipeline) {
        return SdkMeterProvider.builder()
                .setResource(resource(properties.resource()))
                .registerMetricReader(
                        PeriodicMetricReader.builder(exportPipeline.metricExporter())
                                .setInterval(properties.export().queues().metrics().itemMaxAge())
                                .build())
                .build();
    }

    @Bean(destroyMethod = "close")
    SdkLoggerProvider observabilityLoggerProvider(
            ObservabilityProperties properties, OtlpExportPipeline exportPipeline) {
        return SdkLoggerProvider.builder()
                .setResource(resource(properties.resource()))
                .addLogRecordProcessor(exportPipeline.logRecordProcessor())
                .build();
    }

    @Bean
    Sampler observabilitySampler(ObservabilityProperties properties) {
        return Sampler.parentBased(new HybridRouteSampler(properties.sampling()));
    }

    @Bean
    OpenTelemetry openTelemetry(
            SdkTracerProvider tracerProvider,
            SdkMeterProvider meterProvider,
            SdkLoggerProvider loggerProvider) {
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .setLoggerProvider(loggerProvider)
                .build();
    }

    @Bean
    PlatformTracer platformTracer(
            OpenTelemetry openTelemetry, ObservabilityHealthMetrics healthMetrics) {
        Tracer tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
        return new PlatformTracer(tracer, new SpanAttributeRedactor(healthMetrics));
    }

    @Bean
    StructuredJsonLogEncoder structuredJsonLogEncoder(ObservabilityHealthMetrics healthMetrics) {
        return new StructuredJsonLogEncoder(healthMetrics);
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
        return tracerProvider(configuration, sampler, null);
    }

    private static SdkTracerProvider tracerProvider(
            ObservabilityProperties.Resource configuration,
            Sampler sampler,
            @Nullable SpanProcessor processor) {
        var builder =
                SdkTracerProvider.builder()
                        .setResource(resource(configuration))
                        .setSampler(Objects.requireNonNull(sampler, "sampler"));
        if (processor != null) {
            builder.addSpanProcessor(processor);
        }
        return builder.build();
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
