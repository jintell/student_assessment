package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.observability.RequestTelemetry;

class FoundationMetricsTest {

    @Test
    void registersPhaseZeroGoldenSignalsWithBoundedDimensions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FoundationMetrics metrics = new FoundationMetrics(registry);
        RequestTelemetry.RequestMetadata metadata =
                new RequestTelemetry.RequestMetadata(
                        "platform",
                        "getConformanceReference",
                        RequestTelemetry.Audience.WORKFORCE,
                        RequestTelemetry.Operation.READ,
                        RequestTelemetry.RouteClass.STANDARD);

        metrics.recordHttpRequest(
                metadata, FoundationMetrics.HttpMethod.GET, 404, Duration.ofMillis(5));
        metrics.recordHttpRequest(
                metadata, FoundationMetrics.HttpMethod.GET, 503, Duration.ofMillis(7));
        metrics.recordPoolAcquire(FoundationMetrics.Workload.API, Duration.ofMillis(2));
        metrics.updatePoolPending(FoundationMetrics.Workload.API, 3);
        metrics.record(metadata, Duration.ofMillis(4));
        metrics.recordRedisCommand(
                FoundationMetrics.RedisCommand.GET,
                FoundationMetrics.RedisOutcome.SUCCESS,
                Duration.ofMillis(1));
        metrics.recordRedisError(
                FoundationMetrics.RedisCommand.GET, FoundationMetrics.RedisErrorReason.TIMEOUT);

        assertThat(
                        registry.get("http_server_requests")
                                .tag("statusClass", "4xx")
                                .tag("outcome", "client-error")
                                .timer()
                                .count())
                .isEqualTo(1L);
        assertThat(
                        registry.get("http_server_requests")
                                .tag("statusClass", "5xx")
                                .tag("outcome", "server-error")
                                .timer()
                                .count())
                .isEqualTo(1L);
        assertThat(registry.get("db_pool_acquire_duration").timer().count()).isEqualTo(1L);
        assertThat(registry.get("db_pool_pending").tag("workload", "api").gauge().value())
                .isEqualTo(3.0d);
        assertThat(registry.get("db_query_duration").timer().count()).isEqualTo(1L);
        assertThat(registry.get("redis_command_duration").timer().count()).isEqualTo(1L);
        assertThat(registry.get("redis_errors_total").counter().count()).isEqualTo(1.0d);
    }
}
