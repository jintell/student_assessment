package org.meldtech.platform.platform.infra.observability;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.MetricsServiceGrpc;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

final class OtlpGrpcTestSink implements AutoCloseable {

    private static final int CAPTURE_CAPACITY = 4;
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);

    private final BlockingQueue<ExportTraceServiceRequest> traces =
            new ArrayBlockingQueue<>(CAPTURE_CAPACITY);
    private final BlockingQueue<ExportMetricsServiceRequest> metrics =
            new ArrayBlockingQueue<>(CAPTURE_CAPACITY);
    private final BlockingQueue<ExportLogsServiceRequest> logs =
            new ArrayBlockingQueue<>(CAPTURE_CAPACITY);
    private final Server server;

    private OtlpGrpcTestSink() throws IOException {
        server =
                NettyServerBuilder.forPort(0)
                        .addService(new TraceSink())
                        .addService(new MetricSink())
                        .addService(new LogSink())
                        .build()
                        .start();
    }

    static OtlpGrpcTestSink start() throws IOException {
        return new OtlpGrpcTestSink();
    }

    String endpoint() {
        return "http://127.0.0.1:" + server.getPort();
    }

    ExportTraceServiceRequest awaitTrace() {
        return await(traces, "trace");
    }

    ExportMetricsServiceRequest awaitMetric() {
        return await(metrics, "metric");
    }

    ExportLogsServiceRequest awaitLog() {
        return await(logs, "log");
    }

    @Override
    public void close() {
        server.shutdownNow();
        try {
            if (!server.awaitTermination(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("OTLP test sink did not terminate");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while closing OTLP test sink", exception);
        }
    }

    private static <T> T await(BlockingQueue<T> captures, String signal) {
        try {
            T capture = captures.poll(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (capture == null) {
                throw new IllegalStateException("Timed out waiting for OTLP " + signal + " export");
            }
            return capture;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for OTLP " + signal, exception);
        }
    }

    private final class TraceSink extends TraceServiceGrpc.TraceServiceImplBase {

        @Override
        public void export(
                ExportTraceServiceRequest request,
                StreamObserver<ExportTraceServiceResponse> observer) {
            requireCapture(traces, request, "trace");
            observer.onNext(ExportTraceServiceResponse.getDefaultInstance());
            observer.onCompleted();
        }
    }

    private final class MetricSink extends MetricsServiceGrpc.MetricsServiceImplBase {

        @Override
        public void export(
                ExportMetricsServiceRequest request,
                StreamObserver<ExportMetricsServiceResponse> observer) {
            requireCapture(metrics, request, "metric");
            observer.onNext(ExportMetricsServiceResponse.getDefaultInstance());
            observer.onCompleted();
        }
    }

    private final class LogSink extends LogsServiceGrpc.LogsServiceImplBase {

        @Override
        public void export(
                ExportLogsServiceRequest request,
                StreamObserver<ExportLogsServiceResponse> observer) {
            requireCapture(logs, request, "log");
            observer.onNext(ExportLogsServiceResponse.getDefaultInstance());
            observer.onCompleted();
        }
    }

    private static <T> void requireCapture(BlockingQueue<T> captures, T request, String signal) {
        if (!captures.offer(request)) {
            throw new IllegalStateException("OTLP " + signal + " capture queue is full");
        }
    }
}
