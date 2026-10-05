package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SpanAttributeRedactorTest {

    @Test
    void redactsForbiddenAttributesWithoutEvaluatingTheirValues() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        OpenTelemetry telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        SpanBuilder builder = telemetry.getTracer("test").spanBuilder("test.redaction");
        SpanAttributeRedactor redactor = new SpanAttributeRedactor();
        AtomicBoolean secretEvaluated = new AtomicBoolean();

        redactor.set(
                builder,
                "authorizationToken",
                () -> {
                    secretEvaluated.set(true);
                    return "must-not-be-read";
                });
        redactor.set(builder, "candidateEmail", () -> "candidate@example.test");
        redactor.set(builder, "policy_key", () -> "retention-five-year");
        builder.startSpan().end();

        var attributes = exporter.getFinishedSpanItems().getFirst().getAttributes();
        assertThat(secretEvaluated).isFalse();
        assertThat(attributes.get(AttributeKey.stringKey("authorizationToken")))
                .isEqualTo(RedactingJsonSerializer.REDACTED);
        assertThat(attributes.get(AttributeKey.stringKey("candidateEmail")))
                .isEqualTo(RedactingJsonSerializer.REDACTED);
        assertThat(attributes.get(AttributeKey.stringKey("policy_key")))
                .isEqualTo("retention-five-year");
        provider.close();
    }
}
