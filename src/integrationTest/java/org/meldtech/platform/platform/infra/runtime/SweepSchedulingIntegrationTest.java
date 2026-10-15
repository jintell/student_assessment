package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.meldtech.platform.platform.api.SweepBatchResult;
import org.meldtech.platform.platform.api.SweepDefinition;
import org.meldtech.platform.platform.api.SweepLock;
import org.meldtech.platform.platform.api.SweepMetadata;
import org.meldtech.platform.platform.api.SweepStopSignal;
import org.meldtech.platform.platform.infra.runtime.SweepRegistryLoader.RegisteredSweep;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

class SweepSchedulingIntegrationTest {
    @ParameterizedTest
    @EnumSource(SystemActor.class)
    void sweepRunsAsItsDeclaredSystemActor(SystemActor declaredActor) throws Exception {
        var meters = new SimpleMeterRegistry();
        var observed = new AtomicReference<ActorContext>();
        RegisteredSweep sweep =
                sweep(
                        "actor-" + declaredActor.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                        Duration.ofSeconds(1),
                        declaredActor,
                        stop ->
                                Mono.deferContextual(
                                        context -> {
                                            observed.set(context.get(ActorContext.class));
                                            return Mono.just(new SweepBatchResult(1, false));
                                        }));
        var runner = runner(List.of(), meters);
        try {
            runner.start();

            runner.runTick(sweep).toFuture().get(5, TimeUnit.SECONDS);

            ActorContext actor = Objects.requireNonNull(observed.get());
            assertThat(actor.systemActorName()).contains(declaredActor);
            assertThat(actor.tenantId()).isEmpty();
        } finally {
            runner.stop();
            meters.close();
        }
    }

    @Test
    void slowSweepDelaysItsNextTickInsteadOfOverlappingIt() throws Exception {
        var meters = new SimpleMeterRegistry();
        var active = new AtomicInteger();
        var maximumActive = new AtomicInteger();
        var starts = new CopyOnWriteArrayList<Instant>();
        var twoBatchesStarted = new CountDownLatch(2);
        Duration executionTime = Duration.ofMillis(150);
        RegisteredSweep slow =
                sweep(
                        "slow-sweep",
                        Duration.ofMillis(25),
                        stop ->
                                Mono.using(
                                        () -> {
                                            starts.add(Instant.now());
                                            maximumActive.accumulateAndGet(
                                                    active.incrementAndGet(), Math::max);
                                            twoBatchesStarted.countDown();
                                            return active;
                                        },
                                        ignored ->
                                                Mono.delay(executionTime)
                                                        .map(
                                                                tick ->
                                                                        new SweepBatchResult(
                                                                                1, false)),
                                        ignored -> active.decrementAndGet()));
        var runner = runner(List.of(slow), meters);
        try {
            runner.start();

            assertThat(twoBatchesStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(maximumActive).hasValue(1);
            assertThat(Duration.between(starts.get(0), starts.get(1)))
                    .isGreaterThanOrEqualTo(executionTime.minusMillis(25));
        } finally {
            runner.stop();
            meters.close();
        }
    }

    @Test
    void longRunningSweepDoesNotStarveTheRelayLane() throws Exception {
        var meters = new SimpleMeterRegistry();
        var slowStarted = new CountDownLatch(1);
        var relayStarted = new CountDownLatch(1);
        var slowCompleted = new AtomicBoolean();
        Sinks.One<SweepBatchResult> finishSlow = Sinks.one();
        Duration interval = Duration.ofMillis(25);
        RegisteredSweep slow =
                sweep(
                        "slow-sweep",
                        interval,
                        stop -> {
                            slowStarted.countDown();
                            return finishSlow
                                    .asMono()
                                    .doOnSuccess(ignored -> slowCompleted.set(true));
                        });
        RegisteredSweep relay =
                sweep(
                        "outbox-relay",
                        interval,
                        stop -> {
                            relayStarted.countDown();
                            return Mono.just(new SweepBatchResult(1, false));
                        });
        var runner = runner(List.of(slow, relay), meters);
        try {
            runner.start();

            assertThat(slowStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(relayStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(slowCompleted).isFalse();
        } finally {
            finishSlow.tryEmitValue(new SweepBatchResult(1, false));
            runner.stop();
            meters.close();
        }
    }

    private static SweepRunner runner(List<RegisteredSweep> sweeps, SimpleMeterRegistry meters) {
        SweepLock locks = ignored -> Mono.just(handle());
        return new SweepRunner(
                () -> sweeps,
                locks,
                () -> new CorrelationId("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                (context, actor) -> context.put(ActorContext.class, actor),
                Schedulers.newSingle("sweep-scheduling-integration"),
                new SweepLockShutdown(Mono.empty()),
                new SweepTelemetry(meters, () -> Instant.EPOCH));
    }

    private static SweepLock.Handle handle() {
        return new SweepLock.Handle() {
            @Override
            public Mono<Void> assertHeld() {
                return Mono.empty();
            }

            @Override
            public Mono<Void> close() {
                return Mono.empty();
            }
        };
    }

    private static RegisteredSweep sweep(
            String name,
            Duration interval,
            java.util.function.Function<SweepStopSignal, Mono<SweepBatchResult>> batch) {
        return sweep(name, interval, SystemActor.RETENTION_ENGINE, batch);
    }

    private static RegisteredSweep sweep(
            String name,
            Duration interval,
            SystemActor systemActor,
            java.util.function.Function<SweepStopSignal, Mono<SweepBatchResult>> batch) {
        SweepDefinition definition =
                new SweepDefinition() {
                    @Override
                    public SweepMetadata metadata() {
                        return new SweepMetadata(
                                name,
                                "platform",
                                "FEAT-PLAT-006",
                                systemActor,
                                interval,
                                interval,
                                interval,
                                Duration.ofSeconds(2),
                                Duration.ofSeconds(3));
                    }

                    @Override
                    public Mono<SweepBatchResult> runBatch(SweepStopSignal stop) {
                        return batch.apply(stop);
                    }
                };
        return new RegisteredSweep(definition, interval);
    }
}
