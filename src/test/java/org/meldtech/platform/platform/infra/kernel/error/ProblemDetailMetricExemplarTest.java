package org.meldtech.platform.platform.infra.kernel.error;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Clock;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.tracer.common.SpanContext;
import org.junit.jupiter.api.Test;

class ProblemDetailMetricExemplarTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    @Test
    void emittedProblemCounterCarriesTheCurrentTraceExemplar() {
        FixedSpanContext span = new FixedSpanContext();
        PrometheusMeterRegistry registry =
                new PrometheusMeterRegistry(
                        PrometheusConfig.DEFAULT, new PrometheusRegistry(), Clock.SYSTEM, span);
        MicrometerProblemDetailMetrics metrics = new MicrometerProblemDetailMetrics(registry);

        metrics.emitted("CBT-PLAT-INTERNAL");

        String scrape =
                registry.scrape("application/openmetrics-text; version=1.0.0; charset=utf-8");
        assertThat(scrape)
                .contains("problem_detail_emitted_total")
                .contains("trace_id=\"" + TRACE_ID + "\"")
                .contains("span_id=\"" + SPAN_ID + "\"");
        assertThat(span.markedAsExemplar).isTrue();
    }

    private static final class FixedSpanContext implements SpanContext {

        private boolean markedAsExemplar;

        @Override
        public String getCurrentTraceId() {
            return TRACE_ID;
        }

        @Override
        public String getCurrentSpanId() {
            return SPAN_ID;
        }

        @Override
        public boolean isCurrentSpanSampled() {
            return true;
        }

        @Override
        public void markCurrentSpanAsExemplar() {
            markedAsExemplar = true;
        }
    }
}
