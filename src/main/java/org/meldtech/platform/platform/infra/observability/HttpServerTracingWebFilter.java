package org.meldtech.platform.platform.infra.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.meldtech.platform.shared.infra.web.RequestContextPropagation;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.observability.RequestTelemetry;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Order(Ordered.HIGHEST_PRECEDENCE + 10)
final class HttpServerTracingWebFilter implements WebFilter {

    private final PlatformTracer tracer;

    HttpServerTracingWebFilter(PlatformTracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.deferContextual(
                reactorContext -> {
                    CorrelationId correlationId =
                            CorrelationId.parse(
                                    reactorContext.get(
                                            RequestContextPropagation.CORRELATION_ID_KEY));
                    Optional<ActorContext> actor = reactorContext.getOrEmpty(ActorContext.class);
                    Context parent =
                            Objects.requireNonNull(
                                    reactorContext.getOrDefault(Context.class, Context.current()));
                    Span span =
                            tracer.startServerSpan(
                                    exchange.getRequest().getMethod().name(),
                                    RequestTelemetry.RouteClass.STANDARD,
                                    correlationId,
                                    actor,
                                    parent);
                    AtomicBoolean completed = new AtomicBoolean();
                    return chain.filter(exchange)
                            .doOnSuccess(
                                    ignored ->
                                            finishOnce(
                                                    span,
                                                    completed,
                                                    outcome(
                                                            exchange.getResponse()
                                                                    .getStatusCode())))
                            .doOnError(
                                    failure ->
                                            finishOnce(
                                                    span, completed, PlatformTracer.Outcome.ERROR))
                            .doOnCancel(
                                    () ->
                                            finishOnce(
                                                    span,
                                                    completed,
                                                    PlatformTracer.Outcome.CANCELLED))
                            .doFinally(ignored -> span.end())
                            .contextWrite(context -> context.put(Context.class, parent.with(span)));
                });
    }

    private void finishOnce(Span span, AtomicBoolean completed, PlatformTracer.Outcome outcome) {
        if (completed.compareAndSet(false, true)) {
            tracer.finish(span, outcome);
        }
    }

    private static PlatformTracer.Outcome outcome(@Nullable HttpStatusCode status) {
        if (status == null || status.is2xxSuccessful() || status.is3xxRedirection()) {
            return PlatformTracer.Outcome.SUCCESS;
        }
        if (status.is4xxClientError()) {
            return PlatformTracer.Outcome.CLIENT_ERROR;
        }
        return PlatformTracer.Outcome.SERVER_ERROR;
    }
}
