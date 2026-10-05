package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.ActorType;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.meldtech.platform.shared.kernel.observability.RequestTelemetry;

class OpenTelemetryTracerConfigurationTest {

    @Test
    void appliesResourceSpanNameAndMandatoryAttributes() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider =
                SdkTracerProvider.builder()
                        .setResource(
                                OpenTelemetryTracerConfiguration.resource(
                                        new ObservabilityProperties.Resource(
                                                "cbt-platform", "test", "api")))
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        OpenTelemetry openTelemetry =
                OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        Tracer tracer =
                openTelemetry.getTracer(OpenTelemetryTracerConfiguration.INSTRUMENTATION_SCOPE);
        PlatformTracer platformTracer = new PlatformTracer(tracer);

        platformTracer
                .startSliceSpan(metadata(), actor(), io.opentelemetry.context.Context.root())
                .end();

        var span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getName()).isEqualTo("examaccess.verifyPinAndStartAttempt");
        assertThat(
                        span.getResource()
                                .getAttributes()
                                .get(OpenTelemetryTracerConfiguration.SERVICE_NAME))
                .isEqualTo("cbt-platform");
        assertThat(
                        span.getResource()
                                .getAttributes()
                                .get(OpenTelemetryTracerConfiguration.ENVIRONMENT))
                .isEqualTo("test");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("correlationId")))
                .isEqualTo("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("tenantId")))
                .isEqualTo("ad25adad-f989-4a62-9754-3a600e5bf347");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("module")))
                .isEqualTo("examaccess");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("slice")))
                .isEqualTo("verifyPinAndStartAttempt");
        provider.close();
    }

    private static RequestTelemetry.RequestMetadata metadata() {
        return new RequestTelemetry.RequestMetadata(
                "examaccess",
                "verifyPinAndStartAttempt",
                RequestTelemetry.Audience.CANDIDATE,
                RequestTelemetry.Operation.WRITE,
                RequestTelemetry.RouteClass.EXAM_ENTRY);
    }

    private static ActorContext actor() {
        return new ActorContext(
                ActorType.CANDIDATE,
                new ActorId("candidate-7"),
                Optional.of(TenantId.parse("ad25adad-f989-4a62-9754-3a600e5bf347")),
                CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV"),
                SourceIp.parse("127.0.0.1"),
                Optional.empty());
    }
}
