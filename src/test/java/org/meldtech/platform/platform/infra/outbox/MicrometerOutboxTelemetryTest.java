package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.SweepTransactions;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MicrometerOutboxTelemetryTest {

    @Test
    void workerRegistersTheExactPrometheusContract() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            new ApplicationContextRunner()
                    .withUserConfiguration(OutboxTelemetryConfiguration.class)
                    .withBean(io.micrometer.core.instrument.MeterRegistry.class, () -> registry)
                    .withBean(
                            SweepTransactions.class,
                            () -> org.mockito.Mockito.mock(SweepTransactions.class))
                    .withPropertyValues("spring.profiles.active=worker")
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                MicrometerOutboxTelemetry telemetry =
                                        context.getBean(MicrometerOutboxTelemetry.class);
                                updateBacklog(
                                        telemetry,
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
            MicrometerOutboxTelemetry telemetry = telemetry(registry);
            assertThat(registry.get("outbox.backlog.depth").tag("state", "PENDING").gauge().value())
                    .isNaN();
            assertThat(registry.get("outbox.oldest.pending.age.seconds").gauge().value()).isNaN();
            updateBacklog(
                    telemetry,
                    Map.of(MicrometerOutboxTelemetry.OutboxState.PENDING, 7L),
                    Duration.ofSeconds(12));
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    updateBacklog(
                                            telemetry,
                                            Map.of(
                                                    MicrometerOutboxTelemetry.OutboxState.PENDING,
                                                    -1L),
                                            Duration.ZERO));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> updateBacklog(telemetry, Map.of(), Duration.ofSeconds(-1)));
            assertThat(registry.get("outbox.backlog.depth").tag("state", "PENDING").gauge().value())
                    .isEqualTo(7);
            assertThat(registry.get("outbox.oldest.pending.age.seconds").gauge().value())
                    .isEqualTo(12);
            updateBacklog(telemetry, Map.of(), Duration.ZERO);
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
        MicrometerOutboxTelemetry telemetry = telemetry(registry);

        telemetry.writerAppended();
        telemetry.relayPublished();
        telemetry.relayPublishFailed(PublicationFailureReason.BROKER_NACK);
        telemetry.staleClaimsReclaimed(2);
        telemetry.eventFailed();
        telemetry.consumerDuplicate();
        telemetry.relayTick(Duration.ofMillis(25));
        updateBacklog(
                telemetry,
                Map.of(MicrometerOutboxTelemetry.OutboxState.PENDING, 7L),
                Duration.ofSeconds(12));

        assertThat(registry.get("outbox.writer.appended").counter().count()).isEqualTo(1);
        assertThat(registry.get("outbox.relay.published").counter().count()).isEqualTo(1);
        assertThat(registry.get("outbox.backlog.depth").tag("state", "PENDING").gauge().value())
                .isEqualTo(7);
        assertThat(registry.get("outbox.oldest.pending.age.seconds").gauge().value()).isEqualTo(12);
        assertThat(registry.getMeters())
                .extracting(Meter::getId)
                .allSatisfy(id -> assertThat(id.getTag("tenant_id")).isNull());
    }

    @Test
    void failureDoesNotPublishZeroOrExtendFreshnessAndExpiryIsMonotonic() {
        var time = new AtomicLong();
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var telemetry =
                    new MicrometerOutboxTelemetry(registry, Duration.ofSeconds(10), time::get);
            assertThat(registry.scrape())
                    .contains(
                            "outbox_backlog_sample_fresh 0.0",
                            "outbox_backlog_depth{state=\"PENDING\"} NaN");
            telemetry.updateBacklog(
                    new OutboxBacklogSnapshot(
                            7, 2, 3, 4, Duration.ofMillis(12001), Instant.ofEpochSecond(1000)),
                    time.get());
            time.set(Duration.ofSeconds(9).toNanos());
            telemetry.backlogCollectionFailed();
            assertThat(registry.scrape())
                    .contains(
                            "outbox_backlog_sample_fresh 1.0",
                            "outbox_backlog_depth{state=\"PENDING\"} 7.0",
                            "outbox_oldest_pending_age_seconds 12.001",
                            "outbox_backlog_collection_failure_total 1.0");
            time.set(Duration.ofSeconds(10).toNanos());
            assertThat(registry.scrape())
                    .contains(
                            "outbox_backlog_sample_fresh 0.0",
                            "outbox_backlog_depth{state=\"PENDING\"} NaN",
                            "outbox_oldest_pending_age_seconds NaN",
                            "outbox_backlog_last_success_timestamp_seconds 1000.0");
            telemetry.updateBacklog(
                    new OutboxBacklogSnapshot(0, 0, 0, 0, Duration.ZERO, Instant.EPOCH),
                    time.get());
            assertThat(registry.get("outbox.backlog.depth").gauges())
                    .allSatisfy(gauge -> assertThat(gauge.value()).isZero());
        } finally {
            registry.close();
        }
    }

    @Test
    void expiredFormerOwnerCannotHideRecoveryAndRestartHasNoSample() {
        var time = new AtomicLong();
        var former = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var successor = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var first = new MicrometerOutboxTelemetry(former, Duration.ofSeconds(10), time::get);
            var second =
                    new MicrometerOutboxTelemetry(successor, Duration.ofSeconds(10), time::get);
            first.updateBacklog(
                    new OutboxBacklogSnapshot(
                            5001, 0, 0, 0, Duration.ofSeconds(301), Instant.EPOCH),
                    0);
            assertThat(successor.scrape()).contains("outbox_backlog_sample_fresh 0.0");
            time.set(Duration.ofSeconds(10).toNanos());
            second.updateBacklog(
                    new OutboxBacklogSnapshot(0, 0, 0, 0, Duration.ZERO, Instant.EPOCH),
                    time.get());
            assertThat(former.scrape()).contains("outbox_backlog_depth{state=\"PENDING\"} NaN");
            assertThat(successor.scrape()).contains("outbox_backlog_depth{state=\"PENDING\"} 0.0");
        } finally {
            former.close();
            successor.close();
        }
    }

    private static MicrometerOutboxTelemetry telemetry(
            io.micrometer.core.instrument.MeterRegistry registry) {
        return new MicrometerOutboxTelemetry(registry, Duration.ofMinutes(2), System::nanoTime);
    }

    private static void updateBacklog(
            MicrometerOutboxTelemetry telemetry,
            Map<MicrometerOutboxTelemetry.OutboxState, Long> depths,
            Duration age) {
        telemetry.updateBacklog(
                new OutboxBacklogSnapshot(
                        depths.getOrDefault(MicrometerOutboxTelemetry.OutboxState.PENDING, 0L),
                        depths.getOrDefault(MicrometerOutboxTelemetry.OutboxState.CLAIMED, 0L),
                        depths.getOrDefault(MicrometerOutboxTelemetry.OutboxState.PUBLISHED, 0L),
                        depths.getOrDefault(MicrometerOutboxTelemetry.OutboxState.FAILED, 0L),
                        age,
                        Instant.now()),
                System.nanoTime());
    }
}
