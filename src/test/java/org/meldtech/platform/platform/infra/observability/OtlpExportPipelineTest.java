package org.meldtech.platform.platform.infra.observability;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.ReadWriteLogRecord;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OtlpExportPipelineTest {

    @Test
    void queuesAllThreeSignalsForTheirDedicatedExportWorkers() {
        SpanExporter spans = mock(SpanExporter.class);
        MetricExporter metrics = mock(MetricExporter.class);
        LogRecordExporter logs = mock(LogRecordExporter.class);
        when(spans.export(anyCollection())).thenReturn(CompletableResultCode.ofSuccess());
        when(metrics.export(anyCollection())).thenReturn(CompletableResultCode.ofSuccess());
        when(logs.export(anyCollection())).thenReturn(CompletableResultCode.ofSuccess());
        OtlpExportPipeline pipeline =
                OtlpExportPipeline.create(
                        configuration(), () -> Instant.EPOCH, spans, metrics, logs);
        ReadableSpan span = mock(ReadableSpan.class);
        SpanData spanData = mock(SpanData.class);
        when(span.getSpanContext())
                .thenReturn(
                        SpanContext.create(
                                "4bf92f3577b34da6a3ce929d0e0e4736",
                                "00f067aa0ba902b7",
                                TraceFlags.getSampled(),
                                TraceState.getDefault()));
        when(span.toSpanData()).thenReturn(spanData);
        ReadWriteLogRecord logRecord = mock(ReadWriteLogRecord.class);
        LogRecordData logData = mock(LogRecordData.class);
        when(logRecord.toLogRecordData()).thenReturn(logData);
        MetricData metricData = mock(MetricData.class);

        pipeline.spanProcessor().onEnd(span);
        pipeline.logRecordProcessor().onEmit(io.opentelemetry.context.Context.root(), logRecord);
        pipeline.metricExporter().export(List.of(metricData));

        verify(spans, timeout(1_000)).export(List.of(spanData));
        verify(logs, timeout(1_000)).export(List.of(logData));
        verify(metrics, timeout(1_000)).export(List.of(metricData));
        pipeline.spanProcessor().shutdown();
        pipeline.logRecordProcessor().shutdown();
        pipeline.metricExporter().shutdown();
    }

    private static ObservabilityProperties.Export configuration() {
        ObservabilityProperties.Queue queue =
                new ObservabilityProperties.Queue(16, 4, Duration.ofSeconds(30));
        return new ObservabilityProperties.Export(
                Duration.ofSeconds(1),
                new ObservabilityProperties.SignalQueues(queue, queue, queue));
    }
}
