package org.meldtech.platform.platform.infra.kernel.error;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.error.ProblemDetailMetrics;

class MicrometerProblemDetailMetricsTest {

    @Test
    void recordsEmittedCodesAndBoundedFallbackReasons() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerProblemDetailMetrics metrics = new MicrometerProblemDetailMetrics(registry);

        metrics.emitted("CBT-PLAT-INTERNAL");
        metrics.fallback(ProblemDetailMetrics.FallbackReason.CATALOGUE_MISS);

        assertThat(
                        registry.get("problem_detail_emitted_total")
                                .tag("code", "CBT-PLAT-INTERNAL")
                                .counter()
                                .count())
                .isEqualTo(1.0);
        assertThat(
                        registry.get("problem_detail_unmapped_total")
                                .tag("reason", "catalogue_miss")
                                .counter()
                                .count())
                .isEqualTo(1.0);
    }
}
