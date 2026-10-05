package org.meldtech.platform.platform.infra.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.observability.RequestTelemetry;
import org.meldtech.platform.shared.kernel.time.Clock;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

public final class OpenTelemetryRequestTelemetry implements RequestTelemetry {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(OpenTelemetryRequestTelemetry.class);

    private final PlatformTracer tracer;
    private final Clock clock;

    public OpenTelemetryRequestTelemetry(OpenTelemetry openTelemetry, Clock clock) {
        this(
                new PlatformTracer(
                        Objects.requireNonNull(openTelemetry, "openTelemetry")
                                .getTracer(OpenTelemetryTracerConfiguration.INSTRUMENTATION_SCOPE)),
                clock);
    }

    OpenTelemetryRequestTelemetry(PlatformTracer tracer, Clock clock) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public <T> Publisher<T> observe(RequestMetadata metadata, Publisher<T> request) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(request, "request");
        return Flux.deferContextual(
                reactorContext -> {
                    ActorContext actor = reactorContext.get(ActorContext.class);
                    Context parent =
                            Objects.requireNonNull(
                                    reactorContext.getOrDefault(Context.class, Context.current()));
                    Span span = tracer.startSliceSpan(metadata, actor, parent);
                    Instant startedAt = clock.now();
                    RequestQueryContext queryContext = new RequestQueryContext(metadata);
                    AtomicBoolean completed = new AtomicBoolean();
                    return Flux.from(request)
                            .doOnComplete(
                                    () ->
                                            finishOnce(
                                                    span,
                                                    metadata,
                                                    queryContext,
                                                    startedAt,
                                                    completed,
                                                    PlatformTracer.Outcome.SUCCESS))
                            .doOnError(
                                    failure ->
                                            finishOnce(
                                                    span,
                                                    metadata,
                                                    queryContext,
                                                    startedAt,
                                                    completed,
                                                    PlatformTracer.Outcome.ERROR))
                            .doOnCancel(
                                    () ->
                                            finishOnce(
                                                    span,
                                                    metadata,
                                                    queryContext,
                                                    startedAt,
                                                    completed,
                                                    PlatformTracer.Outcome.CANCELLED))
                            .doFinally(ignored -> span.end())
                            .contextWrite(
                                    context ->
                                            context.put(Context.class, parent.with(span))
                                                    .put(RequestQueryContext.class, queryContext));
                });
    }

    private void finishOnce(
            Span span,
            RequestMetadata metadata,
            RequestQueryContext queryContext,
            Instant startedAt,
            AtomicBoolean completed,
            PlatformTracer.Outcome outcome) {
        if (completed.compareAndSet(false, true)) {
            tracer.finish(span, outcome);
            logCompletion(span, metadata, queryContext.count(), startedAt, outcome);
        }
    }

    private void logCompletion(
            Span span,
            RequestMetadata metadata,
            long queryCount,
            Instant startedAt,
            PlatformTracer.Outcome outcome) {
        Instant completedAt = clock.now();
        Duration duration =
                completedAt.isBefore(startedAt)
                        ? Duration.ZERO
                        : Duration.between(startedAt, completedAt);
        LOGGER.atInfo()
                .addKeyValue("traceId", span.getSpanContext().getTraceId())
                .addKeyValue("spanId", span.getSpanContext().getSpanId())
                .addKeyValue("module", metadata.module())
                .addKeyValue("slice", metadata.slice())
                .addKeyValue("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
                .addKeyValue("durationMs", duration.toMillis())
                .addKeyValue("dbQueryCount", queryCount)
                .log("Request completed");
    }
}
