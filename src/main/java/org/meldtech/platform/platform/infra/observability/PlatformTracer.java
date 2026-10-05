package org.meldtech.platform.platform.infra.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.observability.RequestTelemetry;

final class PlatformTracer {

    private final Tracer tracer;
    private final SpanAttributeRedactor attributes;

    PlatformTracer(Tracer tracer) {
        this(tracer, new SpanAttributeRedactor());
    }

    PlatformTracer(Tracer tracer, SpanAttributeRedactor attributes) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        this.attributes = Objects.requireNonNull(attributes, "attributes");
    }

    Span startSliceSpan(
            RequestTelemetry.RequestMetadata metadata, ActorContext actor, Context parent) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(actor, "actor");
        var builder =
                tracer.spanBuilder(metadata.spanName())
                        .setSpanKind(SpanKind.INTERNAL)
                        .setParent(Objects.requireNonNull(parent, "parent"));
        attributes.set(builder, SpanAttributeName.MODULE, metadata::module);
        attributes.set(builder, SpanAttributeName.SLICE, metadata::slice);
        attributes.set(
                builder, SpanAttributeName.CORRELATION_ID, () -> actor.correlationId().toString());
        attributes.set(builder, SpanAttributeName.AUDIENCE, () -> tag(metadata.audience()));
        attributes.set(builder, SpanAttributeName.ACTOR_TYPE, () -> tag(actor.actorType()));
        attributes.set(builder, SpanAttributeName.ACTOR_ID, () -> actor.actorId().toString());
        attributes.set(builder, SpanAttributeName.OPERATION, () -> tag(metadata.operation()));
        actor.tenantId()
                .ifPresent(
                        tenantId ->
                                attributes.set(
                                        builder, SpanAttributeName.TENANT_ID, tenantId::toString));
        return builder.startSpan();
    }

    Span startServerSpan(
            String method,
            RequestTelemetry.RouteClass routeClass,
            CorrelationId correlationId,
            Optional<ActorContext> actor,
            Context parent) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(routeClass, "routeClass");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(actor, "actor");
        var builder =
                tracer.spanBuilder("http.server")
                        .setSpanKind(SpanKind.SERVER)
                        .setParent(Objects.requireNonNull(parent, "parent"));
        attributes.set(builder, SpanAttributeName.HTTP_METHOD, () -> method);
        attributes.set(builder, SpanAttributeName.ROUTE_CLASS, () -> tag(routeClass));
        attributes.set(builder, SpanAttributeName.CORRELATION_ID, correlationId::toString);
        actor.ifPresent(value -> addActorAttributes(builder, value));
        return builder.startSpan();
    }

    Span startDatabaseSpan(String statementName, Context parent) {
        return tracer.spanBuilder(Objects.requireNonNull(statementName, "statementName"))
                .setSpanKind(SpanKind.CLIENT)
                .setParent(Objects.requireNonNull(parent, "parent"))
                .startSpan();
    }

    void finish(Span span, Outcome outcome) {
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(outcome, "outcome");
        span.setAttribute(SpanAttributeName.OUTCOME.key(), tag(outcome));
        if (outcome == Outcome.ERROR || outcome == Outcome.SERVER_ERROR) {
            span.setStatus(StatusCode.ERROR);
        }
    }

    private void addActorAttributes(
            io.opentelemetry.api.trace.SpanBuilder builder, ActorContext actor) {
        attributes.set(builder, SpanAttributeName.ACTOR_TYPE, () -> tag(actor.actorType()));
        attributes.set(builder, SpanAttributeName.ACTOR_ID, () -> actor.actorId().toString());
        actor.tenantId()
                .ifPresent(
                        tenantId ->
                                attributes.set(
                                        builder, SpanAttributeName.TENANT_ID, tenantId::toString));
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    enum Outcome {
        SUCCESS,
        CLIENT_ERROR,
        SERVER_ERROR,
        ERROR,
        CANCELLED
    }
}
