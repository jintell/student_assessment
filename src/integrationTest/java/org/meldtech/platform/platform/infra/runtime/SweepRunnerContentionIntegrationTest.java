package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meldtech.platform.platform.api.SweepBatchResult;
import org.meldtech.platform.platform.api.SweepDefinition;
import org.meldtech.platform.platform.api.SweepLock;
import org.meldtech.platform.platform.api.SweepMetadata;
import org.meldtech.platform.platform.api.SweepStopSignal;
import org.meldtech.platform.platform.infra.outbox.OutboxPostgreSqlIntegrationTestSupport;
import org.meldtech.platform.platform.infra.runtime.SweepRegistryLoader.RegisteredSweep;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

class SweepRunnerContentionIntegrationTest extends OutboxPostgreSqlIntegrationTestSupport {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fourRunnersExecuteExactlyOneBatchAcrossFourTicks(boolean transactionMode)
            throws Exception {
        var runners = new ArrayList<SweepRunner>();
        var meters = new SimpleMeterRegistry();
        var executions = new AtomicInteger();
        try {
            for (int replica = 0; replica < 4; replica++) {
                SweepLock lock =
                        transactionMode
                                ? new TransactionSweepLock(connectionFactory)
                                : new SessionSweepLock(connectionFactory);
                var runner =
                        new SweepRunner(
                                List::of,
                                lock,
                                () -> new CorrelationId("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                                (context, actor) -> context.put(ActorContext.class, actor),
                                Schedulers.newSingle("contending-sweep"),
                                new SweepLockShutdown(Mono.empty()),
                                new SweepTelemetry(meters, () -> Instant.EPOCH));
                runners.add(runner);
                runner.start();
            }
            for (int tick = 0; tick < 4; tick++) {
                var started = new CountDownLatch(1);
                Sinks.One<SweepBatchResult> release = Sinks.one();
                RegisteredSweep sweep = sweep(started, release, executions);
                var leader = runners.get(tick).runTick(sweep).toFuture();
                try {
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                    var losers = new ArrayList<CompletableFuture<Void>>();
                    for (int replica = 0; replica < 4; replica++) {
                        if (replica != tick) {
                            losers.add(runners.get(replica).runTick(sweep).toFuture());
                        }
                    }
                    CompletableFuture.allOf(losers.toArray(CompletableFuture[]::new))
                            .get(5, TimeUnit.SECONDS);
                    assertThat(executions).hasValue(tick + 1);
                } finally {
                    release.tryEmitValue(new SweepBatchResult(1, false));
                    leader.get(5, TimeUnit.SECONDS);
                }
            }
            assertOutcomeEventually(meters, "success", 4);
            assertOutcomeEventually(meters, "skipped_lock_not_acquired", 12);
        } finally {
            runners.forEach(SweepRunner::stop);
            meters.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lockLossStopsAtTheBatchBoundaryAndRecordsTheOutcome(boolean transactionMode)
            throws Exception {
        var meters = new SimpleMeterRegistry();
        var executions = new AtomicInteger();
        var firstBatchStarted = new CountDownLatch(1);
        Sinks.One<SweepBatchResult> finishFirstBatch = Sinks.one();
        SweepLock lock = lock(transactionMode);
        var runner = runner(lock, meters);
        var nextRunner = runner(lock(transactionMode), meters);
        RegisteredSweep sweep =
                sweep(
                        () -> {
                            int execution = executions.incrementAndGet();
                            if (execution == 1) {
                                firstBatchStarted.countDown();
                                return finishFirstBatch.asMono();
                            }
                            return Mono.just(new SweepBatchResult(1, false));
                        });
        try {
            runner.start();
            nextRunner.start();
            var losingTick = runner.runTick(sweep).toFuture();
            assertThat(firstBatchStarted.await(5, TimeUnit.SECONDS)).isTrue();

            terminateSweepLockOwner();
            finishFirstBatch.tryEmitValue(new SweepBatchResult(1, true));
            losingTick.get(5, TimeUnit.SECONDS);

            assertThat(executions).hasValue(1);
            assertOutcomeEventually(meters, "skipped_lock_lost", 1);

            nextRunner.runTick(sweep).toFuture().get(5, TimeUnit.SECONDS);
            assertThat(executions).hasValue(2);
        } finally {
            runner.stop();
            nextRunner.stop();
            meters.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void contendingRunnerSkipsWithoutWaitingForTheWinner(boolean transactionMode) throws Exception {
        var meters = new SimpleMeterRegistry();
        var executions = new AtomicInteger();
        SweepLock.Handle winner =
                Objects.requireNonNull(
                        lock(transactionMode)
                                .tryAcquire("outbox-relay")
                                .block(Duration.ofSeconds(5)));
        var runner = runner(lock(transactionMode), meters);
        RegisteredSweep sweep =
                sweep(
                        () -> {
                            executions.incrementAndGet();
                            return Mono.just(new SweepBatchResult(1, false));
                        });
        try {
            runner.start();
            Instant started = Instant.now();

            runner.runTick(sweep).toFuture().get(5, TimeUnit.SECONDS);

            assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(10));
            assertThat(executions).hasValue(0);
            winner.assertHeld().block(Duration.ofSeconds(5));
            assertOutcomeEventually(meters, "skipped_lock_not_acquired", 1);
        } finally {
            runner.stop();
            winner.close().block(Duration.ofSeconds(5));
            meters.close();
        }
    }

    private SweepLock lock(boolean transactionMode) {
        return transactionMode
                ? new TransactionSweepLock(connectionFactory)
                : new SessionSweepLock(connectionFactory);
    }

    private static void assertOutcomeEventually(
            SimpleMeterRegistry meters, String outcome, double expected) {
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                assertThat(
                                                meters.find("sweep_tick_total")
                                                        .tag("outcome", outcome)
                                                        .counter())
                                        .isNotNull()
                                        .extracting(counter -> counter.count())
                                        .isEqualTo(expected));
    }

    private SweepRunner runner(SweepLock lock, SimpleMeterRegistry meters) {
        return new SweepRunner(
                List::of,
                lock,
                () -> new CorrelationId("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                (context, actor) -> context.put(ActorContext.class, actor),
                Schedulers.newSingle("contending-sweep"),
                new SweepLockShutdown(Mono.empty()),
                new SweepTelemetry(meters, () -> Instant.EPOCH));
    }

    private void terminateSweepLockOwner() throws Exception {
        executeAsClusterOwner(
                """
                SELECT pg_catalog.pg_terminate_backend(lock.pid)
                FROM pg_catalog.pg_locks AS lock
                JOIN platform.sweep_registry AS registry
                  ON lock.classid = ((registry.lock_key >> 32) & 4294967295)::oid
                 AND lock.objid = (registry.lock_key & 4294967295)::oid
                WHERE registry.sweep_name = 'outbox-relay'
                  AND lock.locktype = 'advisory'
                  AND lock.granted
                """);
    }

    private static RegisteredSweep sweep(
            CountDownLatch started, Sinks.One<SweepBatchResult> release, AtomicInteger executions) {
        return sweep(
                () -> {
                    executions.incrementAndGet();
                    started.countDown();
                    return release.asMono();
                });
    }

    private static RegisteredSweep sweep(
            java.util.function.Supplier<Mono<SweepBatchResult>> batch) {
        var interval = Duration.ofMillis(200);
        SweepDefinition definition =
                new SweepDefinition() {
                    @Override
                    public SweepMetadata metadata() {
                        return new SweepMetadata(
                                "outbox-relay",
                                "platform",
                                "FEAT-PLAT-004",
                                SystemActor.OUTBOX_RELAY,
                                interval,
                                interval,
                                interval,
                                Duration.ofSeconds(10),
                                Duration.ofSeconds(15));
                    }

                    @Override
                    public Mono<SweepBatchResult> runBatch(SweepStopSignal stop) {
                        return Mono.defer(batch);
                    }
                };
        return new RegisteredSweep(definition, interval);
    }
}
