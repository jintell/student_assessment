package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OtlpExportIntegrationTest {

    private static final String SERVICE = "cbt-platform-otlp-test";
    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void exportsTracesMetricsAndLogsThroughOtlpGrpcSerialization() throws Exception {
        try (OtlpGrpcTestSink sink = OtlpGrpcTestSink.start();
                SdkTracerProvider tracerProvider = tracerProvider(sink);
                SdkMeterProvider meterProvider = meterProvider(sink);
                SdkLoggerProvider loggerProvider = loggerProvider(sink)) {
            OpenTelemetry telemetry =
                    OpenTelemetrySdk.builder()
                            .setTracerProvider(tracerProvider)
                            .setMeterProvider(meterProvider)
                            .setLoggerProvider(loggerProvider)
                            .build();

            Span span = telemetry.getTracer(SERVICE).spanBuilder("platform.otlpExport").startSpan();
            span.setAttribute("module", "platform");
            span.end();
            telemetry.getMeter(SERVICE).counterBuilder("otlp_export_probe_total").build().add(1);
            telemetry
                    .getLogsBridge()
                    .get(SERVICE)
                    .logRecordBuilder()
                    .setSeverity(Severity.INFO)
                    .setBody("OTLP export probe completed")
                    .emit();

            forceFlush(tracerProvider.forceFlush(), "trace");
            forceFlush(meterProvider.forceFlush(), "metric");
            forceFlush(loggerProvider.forceFlush(), "log");

            assertTrace(sink.awaitTrace());
            assertMetric(sink.awaitMetric());
            assertLog(sink.awaitLog());
        }
    }

    private static SdkTracerProvider tracerProvider(OtlpGrpcTestSink sink) {
        return SdkTracerProvider.builder()
                .setResource(resource())
                .addSpanProcessor(
                        SimpleSpanProcessor.create(
                                OtlpGrpcSpanExporter.builder()
                                        .setEndpoint(sink.endpoint())
                                        .setTimeout(EXPORT_TIMEOUT)
                                        .build()))
                .build();
    }

    private static SdkMeterProvider meterProvider(OtlpGrpcTestSink sink) {
        return SdkMeterProvider.builder()
                .setResource(resource())
                .registerMetricReader(
                        PeriodicMetricReader.builder(
                                        OtlpGrpcMetricExporter.builder()
                                                .setEndpoint(sink.endpoint())
                                                .setTimeout(EXPORT_TIMEOUT)
                                                .build())
                                .setInterval(Duration.ofHours(1))
                                .build())
                .build();
    }

    private static SdkLoggerProvider loggerProvider(OtlpGrpcTestSink sink) {
        return SdkLoggerProvider.builder()
                .setResource(resource())
                .addLogRecordProcessor(
                        SimpleLogRecordProcessor.create(
                                OtlpGrpcLogRecordExporter.builder()
                                        .setEndpoint(sink.endpoint())
                                        .setTimeout(EXPORT_TIMEOUT)
                                        .build()))
                .build();
    }

    private static Resource resource() {
        return Resource.getDefault()
                .merge(
                        Resource.create(
                                Attributes.of(AttributeKey.stringKey("service.name"), SERVICE)));
    }

    private static void assertTrace(ExportTraceServiceRequest request) {
        assertThat(request.getResourceSpansCount()).isEqualTo(1);
        assertThat(request.getResourceSpans(0).getScopeSpans(0).getSpans(0).getName())
                .isEqualTo("platform.otlpExport");
    }

    private static void assertMetric(ExportMetricsServiceRequest request) {
        assertThat(request.getResourceMetricsCount()).isEqualTo(1);
        assertThat(request.getResourceMetrics(0).getScopeMetrics(0).getMetricsList())
                .anyMatch(metric -> metric.getName().equals("otlp_export_probe_total"));
    }

    private static void assertLog(ExportLogsServiceRequest request) {
        assertThat(request.getResourceLogsCount()).isEqualTo(1);
        assertThat(
                        request.getResourceLogs(0)
                                .getScopeLogs(0)
                                .getLogRecords(0)
                                .getBody()
                                .getStringValue())
                .isEqualTo("OTLP export probe completed");
    }

    private static void forceFlush(
            io.opentelemetry.sdk.common.CompletableResultCode result, String signal) {
        result.join(EXPORT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(result.isSuccess()).as("%s export flush", signal).isTrue();
    }
}
