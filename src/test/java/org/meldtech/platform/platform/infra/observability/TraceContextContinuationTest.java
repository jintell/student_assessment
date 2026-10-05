package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.r2dbc.spi.Row;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.testing.observability.ObservabilityTestFixture;

class TraceContextContinuationTest {

    private static final String TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @Test
    void continuesFreshContextFromOutboxColumnsAsAChildSpan() {
        try (ObservabilityTestFixture telemetry =
                ObservabilityTestFixture.create(TraceContextContinuationTest.class)) {
            TraceContextContinuation continuation = continuation(telemetry);
            Row row = mock(Row.class);
            when(row.get("traceparent")).thenReturn(TRACEPARENT);
            when(row.get("tracestate")).thenReturn("vendor=value");
            when(row.get("occurred_at", Instant.class)).thenReturn(NOW.minusSeconds(30));

            TraceContextContinuation.StartedSpan started =
                    continuation.continueFromOutboxRow("outbox.consume", row);
            started.span().end();

            var span = telemetry.finishedSpans().getFirst();
            assertThat(started.decision())
                    .isEqualTo(TraceContextContinuation.ContinuationDecision.CHILD);
            assertThat(span.getParentSpanId()).isEqualTo("00f067aa0ba902b7");
            assertThat(span.getTraceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        }
    }

    @Test
    void linksStaleMessageContextAndStartsANewTraceWhenContextIsAbsent() {
        try (ObservabilityTestFixture telemetry =
                ObservabilityTestFixture.create(TraceContextContinuationTest.class)) {
            TraceContextContinuation continuation = continuation(telemetry);
            TraceContextContinuation.StartedSpan linked =
                    continuation.continueFromMessageHeaders(
                            "broker.consume",
                            Map.of(
                                    "traceparent",
                                    TRACEPARENT,
                                    "tracestate",
                                    "vendor=value",
                                    "occurred_at",
                                    NOW.minus(Duration.ofHours(2)).toString()));
            linked.span().end();
            TraceContextContinuation.StartedSpan root =
                    continuation.continueFromMessageHeaders(
                            "broker.consume", Map.of("occurred_at", NOW.toString()));
            root.span().end();

            var spans = telemetry.finishedSpans();
            assertThat(linked.decision())
                    .isEqualTo(TraceContextContinuation.ContinuationDecision.LINK);
            assertThat(spans.getFirst().getParentSpanId()).isEqualTo("0000000000000000");
            assertThat(spans.getFirst().getLinks()).hasSize(1);
            assertThat(spans.getFirst().getLinks().getFirst().getSpanContext().getSpanId())
                    .isEqualTo("00f067aa0ba902b7");
            assertThat(root.decision())
                    .isEqualTo(TraceContextContinuation.ContinuationDecision.NEW_TRACE);
            assertThat(spans.get(1).getParentSpanId()).isEqualTo("0000000000000000");
            assertThat(spans.get(1).getLinks()).isEmpty();
        }
    }

    private static TraceContextContinuation continuation(ObservabilityTestFixture telemetry) {
        return new TraceContextContinuation(
                telemetry.openTelemetry(), () -> NOW, Duration.ofHours(1));
    }
}
