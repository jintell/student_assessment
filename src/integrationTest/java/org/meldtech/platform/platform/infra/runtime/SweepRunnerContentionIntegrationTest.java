package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
            assertThat(meters.get("sweep.tick.outcome").tag("outcome", "SUCCESS").counter().count())
                    .isEqualTo(4);
            assertThat(
                            meters.get("sweep.tick.outcome")
                                    .tag("outcome", "SKIPPED_LOCK_NOT_ACQUIRED")
                                    .counter()
                                    .count())
                    .isEqualTo(12);
        } finally {
            runners.forEach(SweepRunner::stop);
            meters.close();
        }
    }

    private static RegisteredSweep sweep(
            CountDownLatch started, Sinks.One<SweepBatchResult> release, AtomicInteger executions) {
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
                        return Mono.defer(
                                () -> {
                                    executions.incrementAndGet();
                                    started.countDown();
                                    return release.asMono();
                                });
                    }
                };
        return new RegisteredSweep(definition, interval);
    }
}
