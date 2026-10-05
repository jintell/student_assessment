package org.meldtech.platform.platform.infra.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.r2dbc.spi.Row;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.meldtech.platform.shared.kernel.time.Clock;

public final class TraceContextContinuation {

    private static final String TRACEPARENT = "traceparent";
    private static final String TRACESTATE = "tracestate";
    private static final String OCCURRED_AT = "occurred_at";
    private static final TextMapGetter<Map<String, String>> GETTER =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(Map<String, String> carrier) {
                    return carrier.keySet();
                }

                @Override
                public @Nullable String get(Map<String, String> carrier, String key) {
                    return carrier.get(key);
                }
            };

    private final Tracer tracer;
    private final TextMapPropagator propagator;
    private final Clock clock;
    private final Duration maximumParentAge;

    public TraceContextContinuation(
            OpenTelemetry openTelemetry, Clock clock, Duration maximumParentAge) {
        OpenTelemetry telemetry = Objects.requireNonNull(openTelemetry, "openTelemetry");
        tracer = telemetry.getTracer(OpenTelemetryTracerConfiguration.INSTRUMENTATION_SCOPE);
        propagator = W3CTraceContextPropagator.getInstance();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maximumParentAge = requirePositive(maximumParentAge);
    }

    public StartedSpan continueFromOutboxRow(String spanName, Row row) {
        Objects.requireNonNull(row, "row");
        return start(
                spanName, carrier(row), Optional.ofNullable(row.get(OCCURRED_AT, Instant.class)));
    }

    public StartedSpan continueFromMessageHeaders(String spanName, Map<String, Object> headers) {
        Objects.requireNonNull(headers, "headers");
        return start(spanName, carrier(headers), occurredAt(headers.get(OCCURRED_AT)));
    }

    private StartedSpan start(
            String spanName, Map<String, String> carrier, Optional<Instant> occurredAt) {
        if (Objects.requireNonNull(spanName, "spanName").isBlank()) {
            throw new IllegalArgumentException("Consumer span name must be non-blank");
        }
        Context extracted = propagator.extract(Context.root(), carrier, GETTER);
        var remote = Span.fromContext(extracted).getSpanContext();
        var builder = tracer.spanBuilder(spanName).setSpanKind(SpanKind.CONSUMER);
        ContinuationDecision decision;
        if (!remote.isValid()) {
            builder.setNoParent();
            decision = ContinuationDecision.NEW_TRACE;
        } else if (isFresh(occurredAt)) {
            builder.setParent(extracted);
            decision = ContinuationDecision.CHILD;
        } else {
            builder.setNoParent().addLink(remote);
            decision = ContinuationDecision.LINK;
        }
        return new StartedSpan(builder.startSpan(), decision);
    }

    private boolean isFresh(Optional<Instant> occurredAt) {
        if (occurredAt.isEmpty()) {
            return false;
        }
        Instant now = clock.now();
        Instant eventTime = occurredAt.orElseThrow();
        return !eventTime.isAfter(now)
                && Duration.between(eventTime, now).compareTo(maximumParentAge) <= 0;
    }

    private static Map<String, String> carrier(Row row) {
        Map<String, String> carrier = new HashMap<>();
        put(carrier, TRACEPARENT, row.get(TRACEPARENT));
        put(carrier, TRACESTATE, row.get(TRACESTATE));
        return Map.copyOf(carrier);
    }

    private static Map<String, String> carrier(Map<String, Object> headers) {
        Map<String, String> carrier = new HashMap<>();
        put(carrier, TRACEPARENT, headers.get(TRACEPARENT));
        put(carrier, TRACESTATE, headers.get(TRACESTATE));
        return Map.copyOf(carrier);
    }

    private static void put(Map<String, String> carrier, String name, @Nullable Object value) {
        if (value != null && !value.toString().isBlank()) {
            carrier.put(name, value.toString());
        }
    }

    private static Optional<Instant> occurredAt(@Nullable Object value) {
        if (value instanceof Instant instant) {
            return Optional.of(instant);
        }
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value.toString()));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private static Duration requirePositive(Duration duration) {
        Objects.requireNonNull(duration, "maximumParentAge");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Maximum trace-parent age must be positive");
        }
        return duration;
    }

    public record StartedSpan(Span span, ContinuationDecision decision) {

        public StartedSpan {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(decision, "decision");
        }
    }

    public enum ContinuationDecision {
        CHILD,
        LINK,
        NEW_TRACE
    }
}
