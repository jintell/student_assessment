package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.common.CompletableResultCode;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class ObservabilityHealthMetricsTest {

    @Test
    void registersAndUpdatesTheClosedSelfObservabilitySet() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservabilityHealthMetrics health = new ObservabilityHealthMetrics(registry, properties());

        health.exportAttempt(ObservabilityHealthMetrics.Signal.TRACE);
        health.exportSuccess(ObservabilityHealthMetrics.Signal.TRACE);
        health.exportTimeout(ObservabilityHealthMetrics.Signal.LOG);
        health.dropped(
                ObservabilityHealthMetrics.Signal.TRACE,
                ObservabilityHealthMetrics.DropReason.OVERFLOW,
                2);
        health.dropped(
                ObservabilityHealthMetrics.Signal.LOG,
                ObservabilityHealthMetrics.DropReason.EXPORT_FAILURE,
                1);
        health.redactionRejected(
                ObservabilityHealthMetrics.Surface.SPAN,
                ObservabilityHealthMetrics.RedactionReason.PROHIBITED_FIELD);
        health.queueDepth(ObservabilityHealthMetrics.Signal.METRIC, 7);

        assertThat(counter(registry, "telemetry_export_attempt_total", "signal", "trace"))
                .isEqualTo(1.0d);
        assertThat(counter(registry, "telemetry_export_success_total", "signal", "trace"))
                .isEqualTo(1.0d);
        assertThat(counter(registry, "telemetry_export_timeout_total", "signal", "log"))
                .isEqualTo(1.0d);
        assertThat(counter(registry, "telemetry_span_dropped_total", "reason", "overflow"))
                .isEqualTo(2.0d);
        assertThat(
                        counter(
                                registry,
                                "telemetry_log_event_dropped_total",
                                "reason",
                                "export-failure"))
                .isEqualTo(1.0d);
        assertThat(
                        registry.get("telemetry_redaction_rejection_total")
                                .tag("surface", "span")
                                .tag("reason", "prohibited-field")
                                .counter()
                                .count())
                .isEqualTo(1.0d);
        assertThat(
                        registry.get("telemetry_export_queue_depth")
                                .tag("signal", "metric")
                                .gauge()
                                .value())
                .isEqualTo(7.0d);
        assertThat(
                        registry.get("telemetry_export_queue_capacity")
                                .tag("signal", "metric")
                                .gauge()
                                .value())
                .isEqualTo(32.0d);
    }

    @Test
    void forcedExportFailureIncrementsTheDroppedSignalMetric() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservabilityHealthMetrics health = new ObservabilityHealthMetrics(registry, properties());
        CountDownLatch failureRecorded = new CountDownLatch(1);
        TelemetryHealth recordingHealth = recordingHealth(health, failureRecorded);
        BoundedExportQueue<String> queue =
                new BoundedExportQueue<>(
                        "forced-failure",
                        ObservabilityHealthMetrics.Signal.TRACE,
                        queue(16),
                        Duration.ofMillis(100),
                        Instant::now,
                        ignored -> CompletableResultCode.ofFailure(),
                        () -> {},
                        recordingHealth);

        queue.offer("span");

        assertThat(failureRecorded.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(counter(registry, "telemetry_span_dropped_total", "reason", "export-failure"))
                .isEqualTo(1.0d);
        queue.shutdown();
    }

    @Test
    void forcedRedactionRejectionIncrementsTheRejectionMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservabilityHealthMetrics health = new ObservabilityHealthMetrics(registry, properties());
        RedactingJsonSerializer serializer = new RedactingJsonSerializer(health);

        serializer.serialize(
                "authorizationToken",
                () -> JsonNodeFactory.instance.stringNode("must-not-be-read"));

        assertThat(
                        registry.get("telemetry_redaction_rejection_total")
                                .tag("surface", "log")
                                .tag("reason", "prohibited-field")
                                .counter()
                                .count())
                .isEqualTo(1.0d);
    }

    private static double counter(
            SimpleMeterRegistry registry, String name, String tag, String value) {
        return registry.get(name).tag(tag, value).counter().count();
    }

    private static ObservabilityProperties properties() {
        ObservabilityProperties.Queue traces = queue(16);
        ObservabilityProperties.Queue metrics = queue(32);
        ObservabilityProperties.Queue logs = queue(64);
        ObservabilityProperties properties = mock(ObservabilityProperties.class);
        when(properties.export())
                .thenReturn(
                        new ObservabilityProperties.Export(
                                Duration.ofSeconds(1),
                                new ObservabilityProperties.SignalQueues(traces, metrics, logs)));
        return properties;
    }

    private static ObservabilityProperties.Queue queue(int capacity) {
        return new ObservabilityProperties.Queue(capacity, 4, Duration.ofSeconds(30));
    }

    private static TelemetryHealth recordingHealth(
            ObservabilityHealthMetrics delegate, CountDownLatch failureRecorded) {
        return new TelemetryHealth() {
            @Override
            public void exportAttempt(ObservabilityHealthMetrics.Signal signal) {
                delegate.exportAttempt(signal);
            }

            @Override
            public void dropped(
                    ObservabilityHealthMetrics.Signal signal,
                    ObservabilityHealthMetrics.DropReason reason,
                    int count) {
                delegate.dropped(signal, reason, count);
                failureRecorded.countDown();
            }

            @Override
            public void queueDepth(ObservabilityHealthMetrics.Signal signal, int depth) {
                delegate.queueDepth(signal, depth);
            }
        };
    }
}
