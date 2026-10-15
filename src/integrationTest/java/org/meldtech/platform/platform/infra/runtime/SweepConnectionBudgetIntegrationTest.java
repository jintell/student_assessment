package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.r2dbc.spi.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.SweepBatchResult;
import org.meldtech.platform.platform.api.SweepDefinition;
import org.meldtech.platform.platform.api.SweepMetadata;
import org.meldtech.platform.platform.api.SweepStopSignal;
import org.meldtech.platform.platform.infra.outbox.OutboxPostgreSqlIntegrationTestSupport;
import org.meldtech.platform.platform.infra.persistence.ReferenceSweepPools;
import org.meldtech.platform.platform.infra.runtime.SweepRegistryLoader.RegisteredSweep;
import org.meldtech.platform.shared.infra.web.RequestContextPropagation;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;

class SweepConnectionBudgetIntegrationTest extends OutboxPostgreSqlIntegrationTestSupport {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration INTERVAL = Duration.ofMillis(100);
    private static final List<String> SWEEP_NAMES =
            List.of(
                    "auto-submit",
                    "stale-claim-reclaim",
                    "pin-material-destruction",
                    "retention",
                    "idp-reconciliation",
                    "outbox-relay");

    private ReferenceSweepPools pools;

    @BeforeEach
    void createPools() throws Exception {
        pools = new ReferenceSweepPools(postgres);
    }

    @AfterEach
    void closePools() {
        pools.close();
    }

    @Test
    void simultaneousSweepTicksUseAtMostTwoLocksAndLeaveWorkPoolsAvailable() throws Exception {
        var meters = new SimpleMeterRegistry();
        var scheduler = VirtualTimeScheduler.create();
        var twoLanesStarted = new CountDownLatch(2);
        Sinks.One<SweepBatchResult> finishBatches = Sinks.one();
        List<RegisteredSweep> sweeps =
                SWEEP_NAMES.stream()
                        .map(name -> sweep(name, twoLanesStarted, finishBatches))
                        .toList();
        var shutdown = new SweepLockShutdown(Mono.empty());
        var runner =
                new SweepRunner(
                        () -> sweeps,
                        new SessionSweepLock(pools.locks()),
                        () -> new CorrelationId("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                        new RequestContextPropagation(),
                        scheduler,
                        shutdown,
                        new SweepTelemetry(meters, () -> Instant.EPOCH));
        try {
            runner.start();
            scheduler.advanceTimeBy(INTERVAL);
            assertThat(twoLanesStarted.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            assertThat(runner.pendingSignals()).isEqualTo(4);
            assertThat(pools.lockAcquiredConnections()).isEqualTo(2);

            Connection grading =
                    Objects.requireNonNull(Mono.from(pools.grading().create()).block(WAIT));
            try {
                Connection dispatcher =
                        Objects.requireNonNull(Mono.from(pools.worker().create()).block(WAIT));
                try {
                    assertThat(pools.gradingAcquiredConnections()).isEqualTo(1);
                    assertThat(pools.workerAcquiredConnections()).isEqualTo(1);
                    assertThat(pools.lockAcquiredConnections()).isEqualTo(2);
                } finally {
                    Mono.from(dispatcher.close()).block(WAIT);
                }
            } finally {
                Mono.from(grading.close()).block(WAIT);
            }
        } finally {
            finishBatches.tryEmitValue(new SweepBatchResult(0, false));
            stop(runner, shutdown);
            scheduler.dispose();
            meters.close();
        }
        assertThat(pools.lockAcquiredConnections()).isZero();
    }

    private static RegisteredSweep sweep(
            String name, CountDownLatch started, Sinks.One<SweepBatchResult> finishBatches) {
        SweepDefinition definition =
                new SweepDefinition() {
                    @Override
                    public SweepMetadata metadata() {
                        return new SweepMetadata(
                                name,
                                "platform",
                                "FEAT-PLAT-006",
                                SystemActor.RETENTION_ENGINE,
                                INTERVAL,
                                INTERVAL,
                                INTERVAL,
                                Duration.ofSeconds(2),
                                Duration.ofSeconds(3));
                    }

                    @Override
                    public Mono<SweepBatchResult> runBatch(SweepStopSignal stop) {
                        return Mono.defer(
                                () -> {
                                    started.countDown();
                                    return finishBatches.asMono();
                                });
                    }
                };
        return new RegisteredSweep(definition, INTERVAL);
    }

    private static void stop(SweepRunner runner, SweepLockShutdown shutdown) throws Exception {
        var runnerStopped = new CompletableFuture<Void>();
        var locksStopped = new CompletableFuture<Void>();
        runner.stop(() -> runnerStopped.complete(null));
        shutdown.stop(() -> locksStopped.complete(null));
        CompletableFuture.allOf(runnerStopped, locksStopped)
                .get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }
}
