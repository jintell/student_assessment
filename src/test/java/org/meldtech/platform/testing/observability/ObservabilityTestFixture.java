package org.meldtech.platform.testing.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;

public final class ObservabilityTestFixture implements AutoCloseable {

    private static final Duration UNUSED_PERIODIC_EXPORT_INTERVAL = Duration.ofHours(1);
    private static final long FLUSH_TIMEOUT_SECONDS = 5;

    private final InMemorySpanExporter spanExporter;
    private final InMemoryMetricExporter metricExporter;
    private final SdkTracerProvider tracerProvider;
    private final SdkMeterProvider meterProvider;
    private final OpenTelemetrySdk openTelemetry;
    private final Logger logger;
    private final ListAppender<ILoggingEvent> logAppender;

    private ObservabilityTestFixture(Class<?> loggingSource) {
        spanExporter = InMemorySpanExporter.create();
        metricExporter = InMemoryMetricExporter.create();
        tracerProvider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                        .build();
        meterProvider =
                SdkMeterProvider.builder()
                        .registerMetricReader(
                                PeriodicMetricReader.builder(metricExporter)
                                        .setInterval(UNUSED_PERIODIC_EXPORT_INTERVAL)
                                        .build())
                        .build();
        openTelemetry =
                OpenTelemetrySdk.builder()
                        .setTracerProvider(tracerProvider)
                        .setMeterProvider(meterProvider)
                        .build();
        logger = (Logger) LoggerFactory.getLogger(loggingSource);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    public static ObservabilityTestFixture create(Class<?> loggingSource) {
        return new ObservabilityTestFixture(Objects.requireNonNull(loggingSource, "loggingSource"));
    }

    public OpenTelemetry openTelemetry() {
        return openTelemetry;
    }

    public Tracer tracer() {
        return openTelemetry.getTracer("cbt-platform-test");
    }

    public Meter meter() {
        return openTelemetry.getMeter("cbt-platform-test");
    }

    public List<SpanData> finishedSpans() {
        flush(tracerProvider.forceFlush(), "span");
        return List.copyOf(spanExporter.getFinishedSpanItems());
    }

    public List<MetricData> finishedMetrics() {
        flush(meterProvider.forceFlush(), "metric");
        return List.copyOf(metricExporter.getFinishedMetricItems());
    }

    public List<ILoggingEvent> logEvents() {
        return List.copyOf(logAppender.list);
    }

    @Override
    public void close() {
        logger.detachAppender(logAppender);
        logAppender.stop();
        tracerProvider.close();
        meterProvider.close();
    }

    private static void flush(
            io.opentelemetry.sdk.common.CompletableResultCode result, String signal) {
        result.join(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!result.isSuccess()) {
            throw new IllegalStateException("Failed to flush in-memory " + signal + " telemetry");
        }
    }
}
