package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ObservabilityHealthIndicatorTest {

    @Test
    void reportsExportFailureWithoutMarkingApplicationHealthDown() {
        ObservabilityHealthMetrics metrics =
                new ObservabilityHealthMetrics(new SimpleMeterRegistry(), properties());
        ObservabilityHealthIndicator indicator = new ObservabilityHealthIndicator(metrics);

        assertThat(indicator.health().getStatus().getCode()).isEqualTo("UP");

        metrics.dropped(
                ObservabilityHealthMetrics.Signal.TRACE,
                ObservabilityHealthMetrics.DropReason.EXPORT_FAILURE,
                1);

        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(indicator.health().getDetails().get("signals")).asString().contains("trace");

        metrics.exportSuccess(ObservabilityHealthMetrics.Signal.TRACE);

        assertThat(indicator.health().getStatus().getCode()).isEqualTo("UP");
    }

    private static ObservabilityProperties properties() {
        ObservabilityProperties.Queue queue =
                new ObservabilityProperties.Queue(16, 4, Duration.ofSeconds(30));
        ObservabilityProperties properties = mock(ObservabilityProperties.class);
        when(properties.export())
                .thenReturn(
                        new ObservabilityProperties.Export(
                                Duration.ofSeconds(1),
                                new ObservabilityProperties.SignalQueues(queue, queue, queue)));
        return properties;
    }
}
