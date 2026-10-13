package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.SweepBatchResult;
import org.meldtech.platform.platform.api.SweepDefinition;
import org.meldtech.platform.platform.api.SweepLock;
import org.meldtech.platform.platform.api.SweepMetadata;
import org.meldtech.platform.platform.api.SweepStopSignal;
import org.meldtech.platform.platform.infra.runtime.SweepRegistryLoader.RegisteredSweep;
import org.meldtech.platform.shared.api.ActorContextPropagation;
import org.meldtech.platform.shared.infra.web.RequestContextPropagation;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.CorrelationIdGenerator;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

class SweepRunnerTest {

    private static final CorrelationId ID = new CorrelationId("01ARZ3NDEKTSV4RRFFQ69G5FAV");

    @Test
    void a250SecondTickRetainsOneSignalPerSweepAndDoesNotBlockRelay() {
        var clock = VirtualTimeScheduler.create();
        var batches = new AtomicInteger();
        var ordinary = new AtomicInteger();
        var relay = new AtomicInteger();
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        RegisteredSweep slow =
                sweep(
                        "slow",
                        stop ->
                                Mono.delay(Duration.ofSeconds(1), clock)
                                        .map(
                                                ignored ->
                                                        new SweepBatchResult(
                                                                1,
                                                                batches.incrementAndGet() % 250
                                                                        != 0)));
        RegisteredSweep fast =
                sweep(
                        "fast",
                        stop ->
                                Mono.fromSupplier(
                                        () -> {
                                            ordinary.incrementAndGet();
                                            return new SweepBatchResult(1, false);
                                        }));
        RegisteredSweep outbox =
                sweep(
                        "outbox-relay",
                        stop ->
                                Mono.fromSupplier(
                                        () -> {
                                            relay.incrementAndGet();
                                            return new SweepBatchResult(1, false);
                                        }));
        SweepLock locks =
                name ->
                        Mono.fromSupplier(
                                () -> {
                                    if (!name.equals("outbox-relay")) {
                                        maximum.accumulateAndGet(
                                                active.incrementAndGet(), Math::max);
                                    }
                                    return handle(
                                            Mono.empty(),
                                            Mono.fromRunnable(
                                                    () -> {
                                                        if (!name.equals("outbox-relay")) {
                                                            active.decrementAndGet();
                                                        }
                                                    }));
                                });
        var runner = runner(List.of(slow, fast, outbox), locks, clock);
        try {
            runner.start();
            clock.advanceTimeBy(Duration.ofSeconds(250));
            assertThat(ordinary).hasValue(0);
            assertThat(relay.get()).isGreaterThanOrEqualTo(1200);
            assertThat(runner.pendingSignals()).isEqualTo(2);
            assertThat(runner.coalescedSignals()).isGreaterThan(2000);
            clock.advanceTimeBy(Duration.ofMillis(200));
            assertThat(ordinary.get()).isPositive();
            assertThat(maximum).hasValue(1);
        } finally {
            runner.stop();
        }
        assertThat(active).hasValue(0);
    }

    @Test
    void timeoutKeepsThePermitUntilAsynchronousReleaseCompletes() {
        var clock = VirtualTimeScheduler.create();
        var acquisitions = new AtomicInteger();
        SweepLock locks =
                name ->
                        Mono.fromSupplier(
                                () -> {
                                    acquisitions.incrementAndGet();
                                    return handle(
                                            Mono.empty(),
                                            Mono.delay(Duration.ofMillis(500), clock).then());
                                });
        var runner = runner(List.of(sweep("stuck", stop -> Mono.never())), locks, clock);
        try {
            runner.start();
            clock.advanceTimeBy(Duration.ofMillis(4200));
            assertThat(acquisitions).hasValue(1);
            assertThat(runner.pendingSignals()).isEqualTo(1);
            clock.advanceTimeBy(Duration.ofMillis(500));
            assertThat(acquisitions).hasValue(2);
        } finally {
            runner.stop();
        }
    }

    @Test
    void contentionSkipsWorkAndLockLossPreventsAnotherBatch() {
        var clock = VirtualTimeScheduler.create();
        var attempts = new AtomicInteger();
        var checks = new AtomicInteger();
        var batches = new AtomicInteger();
        var releases = new AtomicInteger();
        Mono<Void> ownershipCheck =
                Mono.defer(
                        () ->
                                checks.incrementAndGet() == 1
                                        ? Mono.empty()
                                        : Mono.error(new IllegalStateException("lost")));
        SweepLock locks =
                name ->
                        Mono.defer(
                                () ->
                                        attempts.incrementAndGet() == 1
                                                ? Mono.empty()
                                                : Mono.just(
                                                        handle(
                                                                ownershipCheck,
                                                                Mono.fromRunnable(
                                                                        releases
                                                                                ::incrementAndGet))));
        var runner = runner(List.of(), locks, clock);
        var sweep =
                sweep(
                        "reference",
                        stop ->
                                Mono.deferContextual(
                                        context -> {
                                            ActorContext actor = context.get(ActorContext.class);
                                            assertThat(actor.systemActorName())
                                                    .contains(SystemActor.RETENTION_ENGINE);
                                            assertThat(actor.tenantId()).isEmpty();
                                            assertThat(actor.correlationId()).isEqualTo(ID);
                                            assertThat(context.<String>get("correlationId"))
                                                    .isEqualTo(ID.toString());
                                            assertThat(context.<String>get("actorType"))
                                                    .isEqualTo(actor.actorType().name());
                                            assertThat(context.<String>get("actorId"))
                                                    .isEqualTo(actor.actorId().toString());
                                            assertThat(context.hasKey("tenantId")).isFalse();
                                            batches.incrementAndGet();
                                            return Mono.just(new SweepBatchResult(1, true));
                                        }));
        try {
            runner.start();
            StepVerifier.create(runner.runTick(sweep)).verifyComplete();
            assertThat(batches).hasValue(0);
            StepVerifier.create(
                            runner.runTick(sweep)
                                    .contextWrite(context -> context.put("tenantId", "inherited")))
                    .verifyComplete();
            assertThat(batches).hasValue(1);
            assertThat(releases).hasValue(1);
        } finally {
            runner.stop();
        }
    }

    @Test
    void coalescingPreservesArrivalOrderAndCapacity() {
        var queue = new PendingSweepQueue(2);
        var first = sweep("first", stop -> Mono.empty());
        var second = sweep("second", stop -> Mono.empty());
        assertThat(queue.offer(first)).isTrue();
        assertThat(queue.offer(second)).isTrue();
        for (int tick = 0; tick < 2500; tick++) {
            assertThat(queue.offer(first)).isTrue();
        }
        assertThat(queue.offer(sweep("third", stop -> Mono.empty()))).isFalse();
        assertThat(queue).hasSize(2);
        assertThat(queue.poll()).isSameAs(first);
        assertThat(queue.poll()).isSameAs(second);
        assertThat(queue.coalescedCount()).isEqualTo(2500);
    }

    @Test
    void runnerIsEnabledOnlyForWorkerAndDisabledRowsHaveNoSource() {
        List<String> started = new ArrayList<>();
        var registry = mock(SweepRegistryLoader.class);
        when(registry.enabledSweeps()).thenReturn(List.of());
        for (String role : List.of("api", "worker", "pindist")) {
            new ApplicationContextRunner()
                    .withUserConfiguration(SweepRunner.class, RequestContextPropagation.class)
                    .withPropertyValues("spring.profiles.active=" + role)
                    .withBean(SweepRegistryLoader.class, () -> registry)
                    .withBean(
                            SweepLock.class,
                            () ->
                                    name -> {
                                        started.add(name);
                                        return Mono.empty();
                                    })
                    .withBean(CorrelationIdGenerator.class, () -> () -> ID)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(context).hasSingleBean(ActorContextPropagation.class);
                                assertThat(context.getBeansOfType(SweepRunner.class))
                                        .hasSize(role.equals("worker") ? 1 : 0);
                            });
        }
        assertThat(started).isEmpty();
    }

    private static SweepRunner runner(
            List<RegisteredSweep> sweeps, SweepLock locks, VirtualTimeScheduler scheduler) {
        return new SweepRunner(
                () -> sweeps, locks, () -> ID, new RequestContextPropagation(), scheduler);
    }

    private static SweepLock.Handle handle(Mono<Void> held, Mono<Void> close) {
        return new SweepLock.Handle() {
            @Override
            public Mono<Void> assertHeld() {
                return held;
            }

            @Override
            public Mono<Void> close() {
                return close;
            }
        };
    }

    private static RegisteredSweep sweep(
            String name, Function<SweepStopSignal, Mono<SweepBatchResult>> body) {
        Duration interval = Duration.ofMillis(name.equals("outbox-relay") ? 200 : 100);
        SweepDefinition definition =
                new SweepDefinition() {
                    @Override
                    public SweepMetadata metadata() {
                        return new SweepMetadata(
                                name,
                                "platform",
                                "FEAT-PLAT-006",
                                SystemActor.RETENTION_ENGINE,
                                interval,
                                interval,
                                interval,
                                Duration.ofSeconds(4),
                                Duration.ofSeconds(300));
                    }

                    @Override
                    public Mono<SweepBatchResult> runBatch(SweepStopSignal signal) {
                        return body.apply(signal);
                    }
                };
        return new RegisteredSweep(definition, interval);
    }
}
