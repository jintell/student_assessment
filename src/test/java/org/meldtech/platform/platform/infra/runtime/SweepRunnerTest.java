package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.SweepBatchResult;
import org.meldtech.platform.platform.api.SweepDefinition;
import org.meldtech.platform.platform.api.SweepLock;
import org.meldtech.platform.platform.api.SweepLockLostException;
import org.meldtech.platform.platform.api.SweepMetadata;
import org.meldtech.platform.platform.api.SweepStopSignal;
import org.meldtech.platform.platform.infra.runtime.SweepRegistryLoader.RegisteredSweep;
import org.meldtech.platform.shared.api.ActorContextPropagation;
import org.meldtech.platform.shared.infra.web.RequestContextPropagation;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.CorrelationIdGenerator;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

class SweepRunnerTest {

    private static final CorrelationId ID = new CorrelationId("01ARZ3NDEKTSV4RRFFQ69G5FAV");

    @Test
    void runnerDistinguishesContentionFromLostOwnershipAndSuccessfulWork() {
        var meters = new SimpleMeterRegistry();
        var scheduler = VirtualTimeScheduler.create();
        var attempt = new AtomicInteger();
        SweepLock locks =
                name ->
                        switch (attempt.getAndIncrement()) {
                            case 0 -> Mono.empty();
                            case 1 ->
                                    Mono.just(
                                            handle(
                                                    Mono.error(new SweepLockLostException()),
                                                    Mono.empty()));
                            default -> Mono.just(handle(Mono.empty(), Mono.empty()));
                        };
        var runner =
                new SweepRunner(
                        List::of,
                        locks,
                        () -> ID,
                        new RequestContextPropagation(),
                        scheduler,
                        new SweepLockShutdown(Mono.empty()),
                        new SweepTelemetry(meters, () -> Instant.ofEpochSecond(123)));
        var sweep = sweep("reference", stop -> Mono.just(new SweepBatchResult(4, false)));
        try {
            runner.start();
            assertThat(runner.isRunning()).isTrue();
            for (int tick = 0; tick < 3; tick++) {
                StepVerifier.create(runner.runTick(sweep)).verifyComplete();
            }
            for (String outcome :
                    List.of("SUCCESS", "SKIPPED_LOCK_NOT_ACQUIRED", "SKIPPED_LOCK_LOST")) {
                assertThat(
                                meters.get("sweep.tick.outcome")
                                        .tag("outcome", outcome)
                                        .counter()
                                        .count())
                        .isEqualTo(1);
            }
            assertThat(meters.get("sweep.batch.size").summary().totalAmount()).isEqualTo(4);
            assertThat(meters.get("sweep.last.success.timestamp").gauge().value()).isEqualTo(123);
        } finally {
            runner.stop();
            meters.close();
        }
    }

    @Test
    void completedTickLetsTheFinalLockPhaseFinishImmediately() {
        var scheduler = VirtualTimeScheduler.create();
        var callbacks = new AtomicInteger();
        var shutdown = new SweepLockShutdown(Mono.empty());
        var runner =
                runner(
                        List.of(),
                        name -> Mono.just(handle(Mono.empty(), Mono.empty())),
                        scheduler,
                        shutdown);
        try {
            runner.start();
            StepVerifier.create(
                            runner.runTick(
                                    sweep(
                                            "reference",
                                            stop -> Mono.just(new SweepBatchResult(1, false)))))
                    .verifyComplete();
            shutdown.stop(callbacks::incrementAndGet);
            assertThat(callbacks).hasValue(1);
        } finally {
            runner.stop();
            shutdown.stop();
        }
    }

    @Test
    void sweepFailuresDoNotLogPayloadDriverTextOrTenantIdentifiers() {
        String sensitive =
                "org.postgresql.Driver password=driver-secret tenant=01950f47-6000-7000-8000-000000000001";
        Logger logger = (Logger) LoggerFactory.getLogger(SweepRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        var scheduler = VirtualTimeScheduler.create();
        var shutdown = new SweepLockShutdown(Mono.empty());
        var runner =
                runner(
                        List.of(),
                        name -> Mono.just(handle(Mono.empty(), Mono.empty())),
                        scheduler,
                        shutdown);
        try {
            runner.start();
            StepVerifier.create(
                            runner.runTick(
                                    sweep(
                                            "reference",
                                            stop ->
                                                    Mono.error(
                                                            new IllegalStateException(sensitive)))))
                    .verifyComplete();

            assertThat(appender.list)
                    .singleElement()
                    .satisfies(
                            event -> {
                                assertThat(event.getFormattedMessage())
                                        .isEqualTo("sweep_tick_failed sweep_name=reference")
                                        .doesNotContain(
                                                "org.postgresql.Driver",
                                                "driver-secret",
                                                "01950f47-6000-7000-8000-000000000001");
                                assertThat(event.getThrowableProxy()).isNull();
                            });
        } finally {
            runner.stop();
            shutdown.stop();
            logger.detachAppender(appender);
            appender.stop();
        }
    }

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
        var shutdown = new SweepLockShutdown(Mono.empty());
        var runner = runner(List.of(slow, fast, outbox), locks, clock, shutdown);
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
            clock.advanceTimeBy(Duration.ofSeconds(1));
            shutdown.stop();
        }
        assertThat(active).hasValue(0);
    }

    @Test
    void shutdownWaitsForCommitOrRollbackAndDiscardsPendingTicks() {
        for (boolean rollback : List.of(false, true)) {
            var clock = VirtualTimeScheduler.create();
            var result = Sinks.<SweepBatchResult>one();
            var stopSignal = new AtomicReference<SweepStopSignal>();
            var batches = new AtomicInteger();
            var events = new ArrayList<String>();
            var sweep =
                    sweep(
                            "reference",
                            signal -> {
                                stopSignal.set(signal);
                                batches.incrementAndGet();
                                return Mono.usingWhen(
                                        Mono.just("transaction"),
                                        transaction -> result.asMono(),
                                        transaction -> boundary(clock, events, "commit"),
                                        (transaction, failure) ->
                                                boundary(clock, events, "rollback"),
                                        transaction -> boundary(clock, events, "cancel"));
                            });
            SweepLock locks =
                    name ->
                            Mono.just(
                                    handle(
                                            Mono.empty(),
                                            Mono.fromRunnable(() -> events.add("release"))));
            var shutdown = new SweepLockShutdown(Mono.empty());
            var runner = runner(List.of(sweep), locks, clock, shutdown);
            runner.start();
            clock.advanceTimeBy(Duration.ofMillis(300));
            assertThat(runner.pendingSignals()).isEqualTo(1);

            runner.stop(() -> events.add("stopped"));
            assertThat(runner.isRunning()).isFalse();
            assertThat(runner.pendingSignals()).isZero();
            SweepStopSignal capturedSignal = Objects.requireNonNull(stopSignal.get());
            assertThat(capturedSignal.isStopRequested()).isTrue();
            assertThat(events).isEmpty();
            if (rollback) {
                result.tryEmitError(new IllegalStateException("rollback requested"));
            } else {
                result.tryEmitValue(new SweepBatchResult(1, true));
            }
            clock.advanceTimeBy(Duration.ofMillis(499));
            assertThat(events).isEmpty();
            clock.advanceTimeBy(Duration.ofMillis(1));
            if (rollback) {
                assertThat(events).containsExactly("rollback", "stopped", "release");
            } else {
                assertThat(events).containsExactly("commit", "stopped");
            }
            shutdown.stop();
            assertThat(events)
                    .containsExactly(rollback ? "rollback" : "commit", "stopped", "release");
            assertThat(batches).hasValue(1);
            assertThat(clock.isDisposed()).isTrue();
        }
    }

    @Test
    void shutdownWaitsForRollbackWhenTheBatchTimesOut() {
        var clock = VirtualTimeScheduler.create();
        var events = new ArrayList<String>();
        var sweep =
                sweep(
                        "reference",
                        signal ->
                                Mono.deferContextual(
                                        context -> {
                                            TransactionCleanup cleanup =
                                                    context.get(TransactionCleanup.class);
                                            return Mono.usingWhen(
                                                    Mono.just("transaction")
                                                            .doOnNext(
                                                                    ignored -> cleanup.acquired()),
                                                    transaction -> Mono.<SweepBatchResult>never(),
                                                    transaction ->
                                                            boundary(clock, events, "commit")
                                                                    .doFinally(
                                                                            ignored ->
                                                                                    cleanup
                                                                                            .released()),
                                                    (transaction, failure) ->
                                                            boundary(clock, events, "rollback")
                                                                    .doFinally(
                                                                            ignored ->
                                                                                    cleanup
                                                                                            .released()),
                                                    transaction ->
                                                            boundary(clock, events, "rollback")
                                                                    .doFinally(
                                                                            ignored ->
                                                                                    cleanup
                                                                                            .released()));
                                        }));
        SweepLock locks =
                name ->
                        Mono.just(
                                handle(
                                        Mono.empty(),
                                        Mono.fromRunnable(() -> events.add("release"))));
        var shutdown = new SweepLockShutdown(Mono.empty());
        var runner = runner(List.of(sweep), locks, clock, shutdown);
        try {
            runner.start();
            clock.advanceTimeBy(Duration.ofMillis(100));
            runner.stop(() -> events.add("stopped"));

            clock.advanceTimeBy(Duration.ofSeconds(4));
            assertThat(events).isEmpty();
            assertThat(clock.isDisposed()).isFalse();
            clock.advanceTimeBy(Duration.ofMillis(500));
            assertThat(events).containsExactly("rollback", "stopped", "release");
            shutdown.stop();
            assertThat(clock.isDisposed()).isTrue();
        } finally {
            clock.dispose();
        }
    }

    @Test
    void shutdownDuringAcquisitionReleasesWithoutStartingABatch() {
        var clock = VirtualTimeScheduler.create();
        var acquired = Sinks.<SweepLock.Handle>one();
        var batches = new AtomicInteger();
        var events = new ArrayList<String>();
        var sweep =
                sweep(
                        "reference",
                        signal -> {
                            batches.incrementAndGet();
                            return Mono.just(new SweepBatchResult(1, false));
                        });
        var shutdown = new SweepLockShutdown(Mono.empty());
        var runner = runner(List.of(sweep), name -> acquired.asMono(), clock, shutdown);
        runner.start();
        clock.advanceTimeBy(Duration.ofMillis(100));
        runner.stop(() -> events.add("stopped"));
        assertThat(events).isEmpty();
        acquired.tryEmitValue(handle(Mono.empty(), Mono.fromRunnable(() -> events.add("release"))));
        assertThat(batches).hasValue(0);
        assertThat(events).containsExactly("stopped");
        shutdown.stop();
        assertThat(events).containsExactly("stopped", "release");
    }

    @Test
    void shutdownIsIdempotentAndRunsBeforeWebDrain() {
        var clock = VirtualTimeScheduler.create();
        var callbacks = new AtomicInteger();
        var shutdown = new SweepLockShutdown(Mono.empty());
        var runner = runner(List.of(), name -> Mono.empty(), clock, shutdown);
        assertThat(runner.getPhase())
                .isEqualTo(WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE + 1);
        runner.start();
        runner.stop(callbacks::incrementAndGet);
        runner.stop(callbacks::incrementAndGet);
        shutdown.stop();
        assertThat(callbacks).hasValue(2);
        assertThat(clock.isDisposed()).isTrue();
    }

    private static Mono<Void> boundary(
            VirtualTimeScheduler clock, List<String> events, String event) {
        return Mono.delay(Duration.ofMillis(500), clock)
                .then(Mono.fromRunnable(() -> events.add(event)));
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
        var shutdown = new SweepLockShutdown(Mono.empty());
        var runner = runner(List.of(sweep("stuck", stop -> Mono.never())), locks, clock, shutdown);
        try {
            runner.start();
            clock.advanceTimeBy(Duration.ofMillis(4200));
            assertThat(acquisitions).hasValue(1);
            assertThat(runner.pendingSignals()).isEqualTo(1);
            clock.advanceTimeBy(Duration.ofMillis(500));
            assertThat(acquisitions).hasValue(2);
        } finally {
            runner.stop();
            shutdown.stop();
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
        var shutdown = new SweepLockShutdown(Mono.empty());
        var runner = runner(List.of(), locks, clock, shutdown);
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
                    .withBean(SweepLockShutdown.class, () -> new SweepLockShutdown(Mono.empty()))
                    .withBean(
                            SweepTelemetry.class,
                            () ->
                                    new SweepTelemetry(
                                            new SimpleMeterRegistry(), () -> Instant.EPOCH))
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
            List<RegisteredSweep> sweeps,
            SweepLock locks,
            VirtualTimeScheduler scheduler,
            SweepLockShutdown shutdown) {
        return new SweepRunner(
                () -> sweeps,
                locks,
                () -> ID,
                new RequestContextPropagation(),
                scheduler,
                shutdown,
                new SweepTelemetry(new SimpleMeterRegistry(), () -> Instant.EPOCH));
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
