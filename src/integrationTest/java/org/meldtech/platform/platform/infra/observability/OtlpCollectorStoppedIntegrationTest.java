package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.testing.observability.ObservabilityTestFixture;
import reactor.core.publisher.Flux;

class OtlpCollectorStoppedIntegrationTest {

    @Test
    void stoppedCollectorDegradesTelemetryWithoutBlockingOrFailingRequests() throws Exception {
        String stoppedEndpoint;
        try (OtlpGrpcTestSink sink = OtlpGrpcTestSink.start()) {
            stoppedEndpoint = sink.endpoint();
        }

        try (ObservabilityTestFixture telemetry =
                ObservabilityTestFixture.create(OtlpCollectorStoppedIntegrationTest.class)) {
            var span = telemetry.tracer().spanBuilder("platform.collectorStopped").startSpan();
            span.end();
            var spanData = telemetry.finishedSpans().getFirst();
            var exporter =
                    OtlpGrpcSpanExporter.builder()
                            .setEndpoint(stoppedEndpoint)
                            .setTimeout(Duration.ofSeconds(2))
                            .build();
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            ObservabilityHealthMetrics health =
                    new ObservabilityHealthMetrics(registry, healthProperties());
            BoundedExportQueue<io.opentelemetry.sdk.trace.data.SpanData> queue =
                    new BoundedExportQueue<>(
                            "stopped-collector",
                            ObservabilityHealthMetrics.Signal.TRACE,
                            new ObservabilityProperties.Queue(2, 1, Duration.ofMinutes(1)),
                            Duration.ofSeconds(2),
                            Instant::now,
                            exporter::export,
                            () -> exporter.shutdown(),
                            health);

            Instant startedAt = Instant.now();
            Integer successfulRequests =
                    Flux.range(0, 100)
                            .map(
                                    ignored -> {
                                        queue.offer(spanData);
                                        return 1;
                                    })
                            .reduce(0, Integer::sum)
                            .block();
            Duration requestLatency = Duration.between(startedAt, Instant.now());

            assertThat(successfulRequests).isEqualTo(100);
            assertThat(requestLatency).isLessThan(Duration.ofMillis(500));
            assertThat(queue.size()).isLessThanOrEqualTo(2);
            assertThat(queue.droppedCount()).isPositive();
            assertThat(
                            registry.get("telemetry_span_dropped_total")
                                    .tag("reason", "overflow")
                                    .counter()
                                    .count())
                    .isPositive();
            queue.shutdown();
        }
    }

    private static ObservabilityProperties healthProperties() {
        ObservabilityProperties.Queue queue =
                new ObservabilityProperties.Queue(2, 1, Duration.ofMinutes(1));
        ObservabilityProperties properties = mock(ObservabilityProperties.class);
        when(properties.export())
                .thenReturn(
                        new ObservabilityProperties.Export(
                                Duration.ofSeconds(2),
                                new ObservabilityProperties.SignalQueues(queue, queue, queue)));
        return properties;
    }
}
