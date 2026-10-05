package org.meldtech.platform.platform.infra.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.meldtech.platform.shared.kernel.observability.RequestTelemetry;

final class HybridRouteSampler implements Sampler {

    static final AttributeKey<String> ROUTE_CLASS = AttributeKey.stringKey("routeClass");
    static final AttributeKey<String> SAMPLING_PRIORITY =
            AttributeKey.stringKey("sampling.priority");

    private final Map<RequestTelemetry.RouteClass, Sampler> routeSamplers;

    HybridRouteSampler(ObservabilityProperties.Sampling configuration) {
        Objects.requireNonNull(configuration, "configuration");
        routeSamplers =
                Map.of(
                        RequestTelemetry.RouteClass.EXAM_ENTRY,
                        requiredFullSampler(
                                configuration.examEntryHeadRatio(), "examEntryHeadRatio"),
                        RequestTelemetry.RouteClass.GRADING,
                        requiredFullSampler(configuration.gradingHeadRatio(), "gradingHeadRatio"),
                        RequestTelemetry.RouteClass.STANDARD,
                        requiredFullSampler(
                                configuration.standardExportRatio(), "standardExportRatio"));
        requireRatio(configuration.standardTailRatio(), "standardTailRatio");
    }

    @Override
    public SamplingResult shouldSample(
            Context parentContext,
            String traceId,
            String name,
            SpanKind spanKind,
            Attributes attributes,
            List<LinkData> parentLinks) {
        RequestTelemetry.RouteClass routeClass = routeClass(attributes.get(ROUTE_CLASS));
        Sampler sampler = Objects.requireNonNull(routeSamplers.get(routeClass));
        SamplingResult decision =
                sampler.shouldSample(
                        parentContext, traceId, name, spanKind, attributes, parentLinks);
        String priority =
                routeClass == RequestTelemetry.RouteClass.STANDARD ? "standard" : "critical";
        return SamplingResult.create(
                decision.getDecision(), Attributes.of(SAMPLING_PRIORITY, priority));
    }

    @Override
    public String getDescription() {
        return "HybridRouteSampler{critical=head-100%,standard=collector-tail}";
    }

    private static Sampler requiredFullSampler(Double ratio, String name) {
        double value = requireRatio(ratio, name);
        if (Double.compare(value, 1.0d) != 0) {
            throw new IllegalArgumentException(
                    name + " must be 1.0 so the collector can make the tail-sampling decision");
        }
        return Sampler.traceIdRatioBased(value);
    }

    private static double requireRatio(Double ratio, String name) {
        if (ratio == null || !Double.isFinite(ratio) || ratio < 0.0d || ratio > 1.0d) {
            throw new IllegalArgumentException(name + " must be between 0.0 and 1.0");
        }
        return ratio;
    }

    private static RequestTelemetry.RouteClass routeClass(String value) {
        if (value == null) {
            return RequestTelemetry.RouteClass.STANDARD;
        }
        try {
            return RequestTelemetry.RouteClass.valueOf(
                    value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown observability route class");
        }
    }
}
