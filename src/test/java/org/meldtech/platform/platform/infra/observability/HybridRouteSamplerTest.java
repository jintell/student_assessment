package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.testing.observability.ObservabilityTestFixture;
import tools.jackson.databind.ObjectMapper;

class HybridRouteSamplerTest {

    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

    @Test
    void marksCriticalRoutesAndExportsStandardRoutesForTheTailDecision() {
        HybridRouteSampler sampler = sampler();

        var examEntry = sample(sampler, "exam_entry");
        var grading = sample(sampler, "grading");
        var standard = sample(sampler, "standard");

        assertThat(examEntry.getDecision()).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        assertThat(grading.getDecision()).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        assertThat(examEntry.getAttributes().get(HybridRouteSampler.SAMPLING_PRIORITY))
                .isEqualTo("critical");
        assertThat(grading.getAttributes().get(HybridRouteSampler.SAMPLING_PRIORITY))
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

    @Test
    void marksErrorsForTheCollectorTailDecision() {
        try (ObservabilityTestFixture telemetry =
                ObservabilityTestFixture.create(HybridRouteSamplerTest.class)) {
            PlatformTracer tracer = new PlatformTracer(telemetry.tracer());
            var span = telemetry.tracer().spanBuilder("platform.failedRequest").startSpan();

            tracer.finish(span, PlatformTracer.Outcome.ERROR);
            span.end();

            assertThat(telemetry.finishedSpans().getFirst().getStatus().getStatusCode())
                    .isEqualTo(StatusCode.ERROR);
        }
    }

    @Test
    void collectorContractRetainsErrorsAndAppliesTheConfiguredStandardRatio() throws Exception {
        var contract =
                new ObjectMapper()
                        .readTree(
                                Files.readString(
                                        Path.of(
                                                "ci/dor/FEAT-OBS-001/"
                                                        + "P3.5-collector-contract.json")));
        var policies = contract.path("tailSampling").path("policies");

        assertThat(contract.path("tailSampling").path("applicationStandardExportRatio").asDouble())
                .isEqualTo(1.0d);
        assertThat(policies.get(0).path("name").stringValue()).isEqualTo("retain-errors");
        assertThat(policies.get(0).path("retention").asDouble()).isEqualTo(1.0d);
        assertThat(policies.get(1).path("name").stringValue()).isEqualTo("retain-critical-routes");
        assertThat(policies.get(1).path("retention").asDouble()).isEqualTo(1.0d);
        assertThat(policies.get(2).path("name").stringValue()).isEqualTo("sample-standard");
        assertThat(policies.get(2).path("retention").asDouble()).isEqualTo(0.1d);
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
