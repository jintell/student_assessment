package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MetricCardinalityGuardTest {

    private static final Path CONTRACT = Path.of("config/observability/metric-cardinality.json");

    @Test
    void acceptsAnExactlyBoundedRegistration() {
        MetricCardinalityGuard guard = MetricCardinalityGuard.from(CONTRACT);

        assertThatNoException()
                .isThrownBy(
                        () ->
                                guard.verifyRegistration(
                                        new MetricCardinalityGuard.Registration(
                                                "db_query_duration",
                                                Set.of("slice", "operation"),
                                                320)));
    }

    @Test
    void rejectsUnboundedUnknownAndZeroCeilingRegistrations() {
        MetricCardinalityGuard guard = MetricCardinalityGuard.from(CONTRACT);

        assertThatThrownBy(
                        () ->
                                guard.verifyRegistration(
                                        new MetricCardinalityGuard.Registration(
                                                "db_query_duration",
                                                Set.of("slice", "operation", "tenantId"),
                                                320)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Metric cardinality registration rejected")
                .hasMessageContaining("forbidden unbounded labels")
                .hasMessageContaining("tenantId");
        assertThatThrownBy(
                        () ->
                                guard.verifyRegistration(
                                        new MetricCardinalityGuard.Registration(
                                                "unreviewed_total", Set.of("outcome"), 4)))
                .hasMessageContaining("not in the approved metric contract");
        assertThatThrownBy(
                        () ->
                                guard.verifyRegistration(
                                        new MetricCardinalityGuard.Registration(
                                                "db_query_duration",
                                                Set.of("slice", "operation"),
                                                0)))
                .hasMessageContaining("no positive series ceiling");
    }

    @Test
    void rejectsCorrelationIdentifierAsAMetricLabel() {
        MetricCardinalityGuard guard = MetricCardinalityGuard.from(CONTRACT);

        assertThatThrownBy(
                        () ->
                                guard.verifyRegistration(
                                        new MetricCardinalityGuard.Registration(
                                                "db_query_duration",
                                                Set.of("slice", "operation", "correlationId"),
                                                320)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Metric cardinality registration rejected")
                .hasMessageContaining("forbidden unbounded labels")
                .hasMessageContaining("correlationId");
    }
}
