package org.meldtech.platform.platform.infra.kernel.error;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.error.ProblemCodeDefinition;
import org.meldtech.platform.shared.kernel.error.ProblemContext;
import org.meldtech.platform.shared.kernel.error.ProblemDetailMapper;
import org.meldtech.platform.shared.kernel.error.ProblemDetailMetrics;

class MicrometerProblemDetailMetricsTest {

    @Test
    void recordsEveryMappedResponseByCode() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerProblemDetailMetrics metrics = new MicrometerProblemDetailMetrics(registry);
        ProblemDetailMapper mapper = mapper(metrics);

        mapper.map(new IllegalArgumentException("hidden"), context());
        mapper.map(new IllegalArgumentException("hidden again"), context());

        assertThat(
                        registry.get("problem_detail_emitted_total")
                                .tag("code", "CBT-PLAT-VALIDATION")
                                .counter()
                                .count())
                .isEqualTo(2.0);
    }

    @Test
    void recordsAnUnmappedExceptionAsADefectSignal() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerProblemDetailMetrics metrics = new MicrometerProblemDetailMetrics(registry);
        ProblemDetailMapper mapper = mapper(metrics);

        mapper.map(new UnsupportedOperationException("provider detail"), context());

        assertThat(registry.get("problem_detail_unmapped_total").counter().count()).isEqualTo(1.0);
        assertThat(
                        registry.get("problem_detail_emitted_total")
                                .tag("code", ProblemDetailMapper.INTERNAL_CODE)
                                .counter()
                                .count())
                .isEqualTo(1.0);
    }

    private static ProblemDetailMapper mapper(ProblemDetailMetrics metrics) {
        return new ProblemDetailMapper(
                Map.of(
                        ProblemDetailMapper.INTERNAL_CODE,
                        definition("internal", "Unexpected error", 500),
                        "CBT-PLAT-VALIDATION",
                        definition("validation", "Validation failed", 400)),
                Map.of(IllegalArgumentException.class, "CBT-PLAT-VALIDATION"),
                metrics);
    }

    private static ProblemCodeDefinition definition(String path, String title, int status) {
        return new ProblemCodeDefinition(
                URI.create("https://errors.meld-tech.com/problems/" + path),
                title,
                status,
                "Fixed public detail.",
                Map.of());
    }

    private static ProblemContext context() {
        return new ProblemContext(
                URI.create("/api/test"), CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV"));
    }
}
