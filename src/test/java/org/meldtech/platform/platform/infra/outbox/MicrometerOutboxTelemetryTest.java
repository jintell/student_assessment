package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MicrometerOutboxTelemetryTest {

    @Test
    void workerRegistersTheExactPrometheusContract() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            new ApplicationContextRunner()
                    .withUserConfiguration(OutboxTelemetryConfiguration.class)
                    .withBean(io.micrometer.core.instrument.MeterRegistry.class, () -> registry)
                    .withPropertyValues("spring.profiles.active=worker")
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                MicrometerOutboxTelemetry telemetry =
                                        context.getBean(MicrometerOutboxTelemetry.class);
                                telemetry.updateBacklog(
                                        Map.of(
                                                MicrometerOutboxTelemetry.OutboxState.PENDING,
                                                5001L),
                                        Duration.ofSeconds(301));
                                telemetry.relayTick(Duration.ofMillis(25));
                                for (PublicationFailureReason reason :
                                        PublicationFailureReason.values()) {
                                    telemetry.relayPublishFailed(reason);
                                }
                                assertThat(registry.scrape())
                                        .contains(
                                                "outbox_backlog_depth{state=\"PENDING\"} 5001.0",
                                                "outbox_oldest_pending_age_seconds 301.0",
                                                "outbox_relay_published_total",
                                                "outbox_relay_publish_failure_total",
                                                "outbox_relay_tick_total 1.0",
                                                "outbox_relay_tick_duration_seconds_count 1",
                                                "outbox_relay_tick_duration_seconds_sum",
                                                "outbox_stale_claim_reclaimed_total",
                                                "outbox_event_failed_total");
                                assertThat(registry.getMeters())
                                        .allSatisfy(
                                                meter -> {
                                                    assertThat(meter.getId().getDescription())
                                                            .isNotBlank();
                                                    assertThat(meter.getId().getTags())
                                                            .allSatisfy(
                                                                    tag ->
                                                                            assertThat(tag.getKey())
                                                                                    .isIn(
                                                                                            "state",
                                                                                            "reason"));
                                                });
                                assertThat(registry.find("outbox.backlog.depth").gauges())
                                        .hasSize(4);
                                assertThat(registry.find("outbox.relay.publish.failure").counters())
                                        .hasSize(4);
                            });
        } finally {
            registry.close();
        }
    }

    @Test
    void unsampledBacklogIsUnknownAndInvalidSamplesRetainPreviousValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            MicrometerOutboxTelemetry telemetry = new MicrometerOutboxTelemetry(registry);
            assertThat(registry.get("outbox.backlog.depth").tag("state", "PENDING").gauge().value())
                    .isNaN();
            assertThat(registry.get("outbox.oldest.pending.age.seconds").gauge().value()).isNaN();
            telemetry.updateBacklog(
                    Map.of(MicrometerOutboxTelemetry.OutboxState.PENDING, 7L),
                    Duration.ofSeconds(12));
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    telemetry.updateBacklog(
                                            Map.of(
                                                    MicrometerOutboxTelemetry.OutboxState.PENDING,
                                                    -1L),
                                            Duration.ZERO));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> telemetry.updateBacklog(Map.of(), Duration.ofSeconds(-1)));
            assertThat(registry.get("outbox.backlog.depth").tag("state", "PENDING").gauge().value())
                    .isEqualTo(7);
            assertThat(registry.get("outbox.oldest.pending.age.seconds").gauge().value())
                    .isEqualTo(12);
            telemetry.updateBacklog(Map.of(), Duration.ZERO);
            assertThat(registry.get("outbox.backlog.depth").tag("state", "PENDING").gauge().value())
                    .isZero();
        } finally {
            registry.close();
        }
    }

    @Test
    void requestAndIsolatedRolesDoNotRegisterWorkerBacklogMeters() {
        for (String profile : new String[] {"api", "pindist"}) {
            new ApplicationContextRunner()
                    .withUserConfiguration(OutboxTelemetryConfiguration.class)
                    .withPropertyValues("spring.profiles.active=" + profile)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(context).doesNotHaveBean(OutboxTelemetry.class);
                            });
        }
    }

    @Test
    void exposesBoundedRelayWriterAndConsumerMetricsWithoutTenantLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerOutboxTelemetry telemetry = new MicrometerOutboxTelemetry(registry);

        telemetry.writerAppended();
        telemetry.relayPublished();
        telemetry.relayPublishFailed(PublicationFailureReason.BROKER_NACK);
        telemetry.staleClaimsReclaimed(2);
        telemetry.eventFailed();
        telemetry.consumerDuplicate();
        telemetry.relayTick(Duration.ofMillis(25));
        telemetry.updateBacklog(
                Map.of(MicrometerOutboxTelemetry.OutboxState.PENDING, 7L), Duration.ofSeconds(12));

        assertThat(registry.get("outbox.writer.appended").counter().count()).isEqualTo(1);
        assertThat(registry.get("outbox.relay.published").counter().count()).isEqualTo(1);
        assertThat(registry.get("outbox.backlog.depth").tag("state", "PENDING").gauge().value())
                .isEqualTo(7);
        assertThat(registry.get("outbox.oldest.pending.age.seconds").gauge().value()).isEqualTo(12);
        assertThat(registry.getMeters())
                .extracting(Meter::getId)
                .allSatisfy(id -> assertThat(id.getTag("tenant_id")).isNull());
    }
}
