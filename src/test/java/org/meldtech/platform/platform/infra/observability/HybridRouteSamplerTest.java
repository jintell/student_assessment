package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import java.util.List;
import org.junit.jupiter.api.Test;

class HybridRouteSamplerTest {

    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

    @Test
    void marksCriticalRoutesAndExportsStandardRoutesForTheTailDecision() {
        HybridRouteSampler sampler = sampler();

        var critical = sample(sampler, "exam_entry");
        var standard = sample(sampler, "standard");

        assertThat(critical.getDecision()).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        assertThat(critical.getAttributes().get(HybridRouteSampler.SAMPLING_PRIORITY))
                .isEqualTo("critical");
        assertThat(standard.getDecision()).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        assertThat(standard.getAttributes().get(HybridRouteSampler.SAMPLING_PRIORITY))
                .isEqualTo("standard");
    }

    @Test
    void rejectsAHeadRatioThatCouldDiscardALaterError() {
        assertThatThrownBy(
                        () ->
                                new HybridRouteSampler(
                                        new ObservabilityProperties.Sampling(
                                                1.0d, 1.0d, 0.1d, 0.1d)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collector can make the tail-sampling decision");
    }

    private static io.opentelemetry.sdk.trace.samplers.SamplingResult sample(
            HybridRouteSampler sampler, String routeClass) {
        return sampler.shouldSample(
                Context.root(),
                TRACE_ID,
                "platform.request",
                SpanKind.SERVER,
                Attributes.of(HybridRouteSampler.ROUTE_CLASS, routeClass),
                List.of());
    }

    private static HybridRouteSampler sampler() {
        return new HybridRouteSampler(new ObservabilityProperties.Sampling(1.0d, 1.0d, 1.0d, 0.1d));
    }
}
