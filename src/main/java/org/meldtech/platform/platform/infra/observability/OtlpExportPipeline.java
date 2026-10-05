package org.meldtech.platform.platform.infra.observability;

import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.logs.ReadWriteLogRecord;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import org.meldtech.platform.shared.kernel.time.Clock;

final class OtlpExportPipeline {

    private final SpanProcessor spanProcessor;
    private final MetricExporter metricExporter;
    private final LogRecordProcessor logRecordProcessor;

    private OtlpExportPipeline(
            SpanProcessor spanProcessor,
            MetricExporter metricExporter,
            LogRecordProcessor logRecordProcessor) {
        this.spanProcessor = spanProcessor;
        this.metricExporter = metricExporter;
        this.logRecordProcessor = logRecordProcessor;
    }

    static OtlpExportPipeline create(
            ObservabilityProperties properties, Clock clock, ObservabilityHealthMetrics health) {
        Objects.requireNonNull(properties, "properties");
        Duration timeout = properties.export().timeout();
        var endpoints = properties.collector().endpoints();
        var spanBuilder =
                OtlpGrpcSpanExporter.builder()
                        .setEndpoint(endpoints.traces().toString())
                        .setTimeout(timeout);
        var metricBuilder =
                OtlpGrpcMetricExporter.builder()
                        .setEndpoint(endpoints.metrics().toString())
                        .setTimeout(timeout);
        var logBuilder =
                OtlpGrpcLogRecordExporter.builder()
                        .setEndpoint(endpoints.logs().toString())
                        .setTimeout(timeout);
        if (!Boolean.TRUE.equals(properties.collector().tls().enabled())) {
            return create(
                    properties.export(),
                    clock,
                    spanBuilder.build(),
                    metricBuilder.build(),
                    logBuilder.build(),
                    health);
        }
        try (OtlpTlsMaterial tls = OtlpTlsMaterial.load(properties.collector().tls())) {
            tls.configure(spanBuilder);
            tls.configure(metricBuilder);
            tls.configure(logBuilder);
            return create(
                    properties.export(),
                    clock,
                    spanBuilder.build(),
                    metricBuilder.build(),
                    logBuilder.build(),
                    health);
        }
    }

    static OtlpExportPipeline create(
            ObservabilityProperties.Export configuration,
            Clock clock,
            SpanExporter spans,
            MetricExporter metrics,
            LogRecordExporter logs) {
        return create(configuration, clock, spans, metrics, logs, TelemetryHealth.NOOP);
    }

    private static OtlpExportPipeline create(
            ObservabilityProperties.Export configuration,
            Clock clock,
            SpanExporter spans,
            MetricExporter metrics,
            LogRecordExporter logs,
            TelemetryHealth health) {
        Objects.requireNonNull(configuration, "configuration");
        var queues = Objects.requireNonNull(configuration.queues(), "queues");
        return new OtlpExportPipeline(
                new QueuedSpanProcessor(
                        queues.traces(), configuration.timeout(), clock, spans, health),
                new QueuedMetricExporter(
                        queues.metrics(), configuration.timeout(), clock, metrics, health),
                new QueuedLogRecordProcessor(
                        queues.logs(), configuration.timeout(), clock, logs, health));
    }

    SpanProcessor spanProcessor() {
        return spanProcessor;
    }

    MetricExporter metricExporter() {
        return metricExporter;
    }

    LogRecordProcessor logRecordProcessor() {
        return logRecordProcessor;
    }

    private static final class QueuedSpanProcessor implements SpanProcessor {

        private final BoundedExportQueue<SpanData> queue;

        private QueuedSpanProcessor(
                ObservabilityProperties.Queue configuration,
                Duration timeout,
                Clock clock,
                SpanExporter exporter,
                TelemetryHealth health) {
            queue =
                    new BoundedExportQueue<>(
                            "traces",
                            ObservabilityHealthMetrics.Signal.TRACE,
                            configuration,
                            timeout,
                            clock,
                            exporter::export,
                            exporter::shutdown,
                            health);
        }

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {}

        @Override
        public boolean isStartRequired() {
            return false;
        }

        @Override
        public void onEnd(ReadableSpan span) {
            if (span.getSpanContext().isSampled()) {
                queue.offer(span.toSpanData());
            }
        }

        @Override
        public boolean isEndRequired() {
            return true;
        }

        @Override
        public CompletableResultCode forceFlush() {
            return queue.forceFlush();
        }

        @Override
        public CompletableResultCode shutdown() {
            return queue.shutdown();
        }
    }

    private static final class QueuedLogRecordProcessor implements LogRecordProcessor {

        private final BoundedExportQueue<LogRecordData> queue;

        private QueuedLogRecordProcessor(
                ObservabilityProperties.Queue configuration,
                Duration timeout,
                Clock clock,
                LogRecordExporter exporter,
                TelemetryHealth health) {
            queue =
                    new BoundedExportQueue<>(
                            "logs",
                            ObservabilityHealthMetrics.Signal.LOG,
                            configuration,
                            timeout,
                            clock,
                            exporter::export,
                            exporter::shutdown,
                            health);
        }

        @Override
        public void onEmit(Context context, ReadWriteLogRecord logRecord) {
            queue.offer(logRecord.toLogRecordData());
        }

        @Override
        public CompletableResultCode forceFlush() {
            return queue.forceFlush();
        }

        @Override
        public CompletableResultCode shutdown() {
            return queue.shutdown();
        }
    }

    private static final class QueuedMetricExporter implements MetricExporter {

        private final MetricExporter delegate;
        private final BoundedExportQueue<MetricData> queue;

        private QueuedMetricExporter(
                ObservabilityProperties.Queue configuration,
                Duration timeout,
                Clock clock,
                MetricExporter delegate,
                TelemetryHealth health) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            queue =
                    new BoundedExportQueue<>(
                            "metrics",
                            ObservabilityHealthMetrics.Signal.METRIC,
                            configuration,
                            timeout,
                            clock,
                            delegate::export,
                            delegate::shutdown,
                            health);
        }

        @Override
        public CompletableResultCode export(Collection<MetricData> metrics) {
            metrics.forEach(queue::offer);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
            return delegate.getAggregationTemporality(instrumentType);
        }

        @Override
        public Aggregation getDefaultAggregation(InstrumentType instrumentType) {
            return delegate.getDefaultAggregation(instrumentType);
        }

        @Override
        public CompletableResultCode flush() {
            return queue.forceFlush();
        }

        @Override
        public CompletableResultCode shutdown() {
            return queue.shutdown();
        }
    }
}
