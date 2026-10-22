package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.SweepBatchResult;
import org.meldtech.platform.platform.api.SweepDefinition;
import org.meldtech.platform.platform.api.SweepMetadata;
import org.meldtech.platform.platform.api.SweepStopSignal;
import org.meldtech.platform.platform.api.SweepTransactions;
import org.meldtech.platform.platform.infra.outbox.OutboxPostgreSqlIntegrationTestSupport;
import org.meldtech.platform.platform.infra.persistence.ReferenceSweepPools;
import org.meldtech.platform.shared.infra.web.RequestContextPropagation;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

class OutboxBacklogSchedulingIntegrationTest extends OutboxPostgreSqlIntegrationTestSupport {
    @Test
    void productionSweepCollectsBesideFailingRelayAndPassesManifestWithExistingPools()
            throws Exception {
        executeAsClusterOwner(
                """
                UPDATE platform.sweep_registry SET enabled = true, default_interval_ms = 1000
                WHERE sweep_name = 'outbox-backlog';
                UPDATE platform.sweep_registry SET enabled = true WHERE sweep_name = 'outbox-relay';
                """);
        var meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var scheduler = VirtualTimeScheduler.create();
        try (var pools = new ReferenceSweepPools(postgres)) {
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            Class.forName(
                                    "org.meldtech.platform.platform.infra.outbox.OutboxTelemetryConfiguration"))
                    .withBean(MeterRegistry.class, () -> meters)
                    .withBean(SweepTransactions.class, pools::transactions)
                    .withBean(
                            RuntimeManifestVerifier.class,
                            () ->
                                    new RuntimeManifestVerifier(
                                            org.meldtech.platform.platform.api.RuntimeRole.WORKER,
                                            RuntimeRoleManifest.load(
                                                    org.meldtech.platform.platform.api.RuntimeRole
                                                            .WORKER)))
                    .withPropertyValues(
                            "spring.profiles.active=worker", "cbt.outbox.backlog.interval=1s")
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                SweepDefinition collector = context.getBean(SweepDefinition.class);
                                var registry =
                                        new SweepRegistryLoader(
                                                pools.worker(),
                                                List.of(collector, new FailingRelay()));
                                registry.afterSingletonsInstantiated();
                                var shutdown = new SweepLockShutdown(Mono.empty());
                                var runtime =
                                        new SweepRunner(
                                                registry::enabledSweeps,
                                                new SessionSweepLock(pools.locks()),
                                                () ->
                                                        new CorrelationId(
                                                                "01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                                                new RequestContextPropagation(),
                                                scheduler,
                                                shutdown,
                                                new SweepTelemetry(meters, Instant::now));
                                try {
                                    runtime.start();
                                    scheduler.advanceTimeBy(Duration.ofSeconds(1));
                                    awaitSamples(meters, 1);
                                    assertThat(meters.scrape())
                                            .contains(
                                                    "outbox_backlog_depth{state=\"PENDING\"} 0.0");
                                    scheduler.advanceTimeBy(Duration.ofSeconds(1));
                                    awaitSamples(meters, 2);
                                    assertThat(
                                                    meters.get("sweep_tick_total")
                                                            .tag("sweep", "outbox-relay")
                                                            .tag("outcome", "failed")
                                                            .counter()
                                                            .count())
                                            .isGreaterThan(0);
                                    assertThat(pools.workerAcquiredConnections())
                                            .isLessThanOrEqualTo(4);
                                    assertThat(pools.lockAcquiredConnections())
                                            .isLessThanOrEqualTo(2);
                                    assertThat(pools.gradingAcquiredConnections()).isZero();
                                } finally {
                                    var stopped = new CompletableFuture<Void>();
                                    runtime.stop(() -> stopped.complete(null));
                                    stopped.get(5, TimeUnit.SECONDS);
                                    var unlocked = new CompletableFuture<Void>();
                                    shutdown.stop(() -> unlocked.complete(null));
                                    unlocked.get(5, TimeUnit.SECONDS);
                                }
                                assertThat(pools.acquiredConnections()).isZero();
                            });
        } finally {
            scheduler.dispose();
            meters.close();
            executeAsClusterOwner(
                    """
                    UPDATE platform.sweep_registry SET enabled = false, default_interval_ms = 60000
                    WHERE sweep_name = 'outbox-backlog';
                    UPDATE platform.sweep_registry SET enabled = false WHERE sweep_name = 'outbox-relay';
                    """);
        }
    }

    private static void awaitSamples(MeterRegistry meters, int count) {
        StepVerifier.create(
                        Flux.interval(Duration.ofMillis(10))
                                .filter(
                                        ignored -> {
                                            var counter =
                                                    meters.find("sweep_tick_total")
                                                            .tag("sweep", "outbox-backlog")
                                                            .tag("outcome", "success")
                                                            .counter();
                                            return counter != null && counter.count() >= count;
                                        })
                                .next())
                .expectNextCount(1)
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    private static final class FailingRelay implements SweepDefinition {
        @Override
        public SweepMetadata metadata() {
            return new SweepMetadata(
                    "outbox-relay",
                    "outbox",
                    "FEAT-PLAT-004",
                    SystemActor.OUTBOX_RELAY,
                    Duration.ofMillis(200),
                    Duration.ofMillis(200),
                    Duration.ofMillis(200),
                    Duration.ofSeconds(4),
                    Duration.ofSeconds(4));
        }

        @Override
        public Mono<SweepBatchResult> runBatch(SweepStopSignal stopSignal) {
            return Mono.error(new IllegalStateException("broker unavailable"));
        }
    }
}
