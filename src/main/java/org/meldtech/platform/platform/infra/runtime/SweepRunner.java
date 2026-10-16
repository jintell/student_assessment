package org.meldtech.platform.platform.infra.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.meldtech.platform.platform.api.RoleScoped;
import org.meldtech.platform.platform.api.SweepBatchResult;
import org.meldtech.platform.platform.api.SweepDefinition;
import org.meldtech.platform.platform.api.SweepLock;
import org.meldtech.platform.platform.api.SweepMetadata;
import org.meldtech.platform.platform.api.SweepStopSignal;
import org.meldtech.platform.platform.infra.runtime.SweepRegistryLoader.RegisteredSweep;
import org.meldtech.platform.shared.api.ActorContextPropagation;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.CorrelationIdGenerator;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Owns the two subscription permits across acquisition, bounded work and asynchronous release. */
@Component
@RoleScoped("worker")
public final class SweepRunner implements SmartLifecycle, RuntimeIntervalSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(SweepRunner.class);
    private final Supplier<List<RegisteredSweep>> registry;
    private final SweepLock locks;
    private final CorrelationIdGenerator correlationIds;
    private final ActorContextPropagation contexts;
    private final Scheduler scheduler;
    private final SweepLockShutdown lockShutdown;
    private final SweepTelemetry telemetry;
    private final List<Lane> lanes = new ArrayList<>();
    private SweepTelemetry.@Nullable Shutdown shutdownTelemetry = null;
    private boolean stopping;
    private volatile boolean running;

    @Autowired
    public SweepRunner(
            SweepRegistryLoader registry,
            SweepLock locks,
            CorrelationIdGenerator correlationIds,
            ActorContextPropagation contexts,
            SweepLockShutdown lockShutdown,
            SweepTelemetry telemetry) {
        this(
                registry::enabledSweeps,
                locks,
                correlationIds,
                contexts,
                Schedulers.newSingle("sweep-ticks"),
                lockShutdown,
                telemetry);
    }

    SweepRunner(
            Supplier<List<RegisteredSweep>> registry,
            SweepLock locks,
            CorrelationIdGenerator correlationIds,
            ActorContextPropagation contexts,
            Scheduler scheduler,
            SweepLockShutdown lockShutdown,
            SweepTelemetry telemetry) {
        this.registry = registry;
        this.locks = locks;
        this.correlationIds = correlationIds;
        this.contexts = contexts;
        this.scheduler = scheduler;
        this.lockShutdown = lockShutdown;
        this.telemetry = telemetry;
    }

    @Override
    public synchronized void start() {
        if (stopping) {
            throw new IllegalStateException("A stopped sweep runner cannot restart");
        }
        if (running) {
            return;
        }
        List<RegisteredSweep> enabled = registry.get();
        running = true;
        startLane(enabled.stream().filter(sweep -> !isRelay(sweep)).toList());
        startLane(enabled.stream().filter(SweepRunner::isRelay).toList());
    }

    private void startLane(List<RegisteredSweep> sweeps) {
        if (sweeps.isEmpty()) {
            return;
        }
        var pending = new PendingSweepQueue(sweeps.size());
        Sinks.Many<RegisteredSweep> signals = Sinks.many().unicast().onBackpressureBuffer(pending);
        var lane = new Lane(pending, Disposables.swap(), signals, Sinks.empty());
        lanes.add(lane);
        // These are the lane's two owned subscriptions, never detached batch subscriptions.
        signals.asFlux()
                .concatMap(this::runTick, 0)
                .subscribe(
                        ignored -> {},
                        failure -> {
                            lane.drained().tryEmitEmpty();
                            sourceFailed();
                        },
                        () -> lane.drained().tryEmitEmpty());
        lane.source()
                .update(
                        Flux.fromIterable(sweeps)
                                .flatMap(
                                        sweep ->
                                                RuntimeTickSources.sweep(
                                                                sweep.interval(), scheduler)
                                                        .onBackpressureLatest()
                                                        .map(ignored -> sweep),
                                        sweeps.size(),
                                        1)
                                .subscribe(
                                        sweep -> {
                                            synchronized (SweepRunner.this) {
                                                if (running
                                                        && signals.tryEmitNext(sweep).isFailure()) {
                                                    sourceFailed();
                                                }
                                            }
                                        },
                                        failure -> sourceFailed()));
    }

    Mono<Void> runTick(RegisteredSweep sweep) {
        return Mono.defer(
                        () -> {
                            if (!running) {
                                return Mono.empty();
                            }
                            SweepDefinition definition = sweep.definition();
                            SweepTelemetry.Tick observation =
                                    telemetry.start(definition.metadata().name());
                            SweepLockShutdown.Tick tick = lockShutdown.beginTick();
                            var cleanup = new TransactionCleanup();
                            SweepStopSignal stop = () -> !running;
                            ActorContext actor =
                                    ActorContext.platformSystem(
                                            definition.metadata().systemActor(),
                                            correlationIds.generate(),
                                            new SourceIp("::1"));
                            return Mono.usingWhen(
                                            locks.tryAcquire(definition.metadata().name())
                                                    .doOnNext(ignored -> observation.acquired()),
                                            handle ->
                                                    batch(definition, handle, stop, observation)
                                                            .expand(
                                                                    result ->
                                                                            result.moreAvailable()
                                                                                            && !stop
                                                                                                    .isStopRequested()
                                                                                    ? batch(
                                                                                            definition,
                                                                                            handle,
                                                                                            stop,
                                                                                            observation)
                                                                                    : Mono.empty())
                                                            .then()
                                                            .timeout(
                                                                    definition
                                                                            .metadata()
                                                                            .maximumTickDuration(),
                                                                    scheduler)
                                                            .onErrorResume(
                                                                    failure ->
                                                                            cleanup.await()
                                                                                    .then(
                                                                                            Mono
                                                                                                    .error(
                                                                                                            failure)))
                                                            .then(Mono.defer(cleanup::await))
                                                            .doOnTerminate(tick::bodyFinished),
                                            handle -> lockShutdown.release(handle, false),
                                            (handle, failure) -> lockShutdown.release(handle, true),
                                            handle -> lockShutdown.release(handle, true))
                                    .doOnError(observation::failed)
                                    .doFinally(
                                            ignored -> {
                                                observation.finish(ignored, stop.isStopRequested());
                                                tick.finished();
                                            })
                                    .contextWrite(
                                            context ->
                                                    contexts.write(context, actor)
                                                            .put(TransactionCleanup.class, cleanup)
                                                            .put(
                                                                    SweepMetadata.class,
                                                                    definition.metadata()));
                        })
                .onErrorResume(
                        failure -> {
                            LOGGER.warn(
                                    "sweep_tick_failed " + SweepLogPolicy.SWEEP_NAME + "={}",
                                    SweepLogPolicy.safeSweepName(
                                            sweep.definition().metadata().name()));
                            return Mono.empty();
                        });
    }

    private Mono<SweepBatchResult> batch(
            SweepDefinition definition,
            SweepLock.Handle handle,
            SweepStopSignal stop,
            SweepTelemetry.Tick observation) {
        return Mono.defer(
                () -> {
                    if (stop.isStopRequested()) {
                        return Mono.empty();
                    }
                    Mono<SweepBatchResult> work =
                            Mono.defer(
                                    () -> {
                                        if (stop.isStopRequested()) {
                                            return Mono.empty();
                                        }
                                        return definition
                                                .runBatch(stop)
                                                .switchIfEmpty(
                                                        Mono.error(
                                                                new IllegalStateException(
                                                                        "Sweep batch returned no result")));
                                    });
                    return handle.assertHeld()
                            .then(work)
                            .timeout(definition.metadata().maximumBatchDuration(), scheduler)
                            .flatMap(result -> handle.assertHeld().thenReturn(result))
                            .doOnNext(result -> observation.batch(result.processedCount()));
                });
    }

    private void sourceFailed() {
        LOGGER.error("sweep_signal_source_failed action=stop_runner");
        stop();
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public synchronized void stop(Runnable callback) {
        running = false;
        lockShutdown.beginDrain().subscribe(ignored -> {}, failure -> callback.run(), callback);
        if (stopping) {
            return;
        }
        stopping = true;
        shutdownTelemetry = telemetry.startShutdown(pendingSignals());
        for (Lane lane : lanes) {
            lane.source().dispose();
            lane.pending().clear();
            lane.signals().tryEmitComplete();
        }
        Mono.when(lanes.stream().map(lane -> lane.drained().asMono()).toList())
                .subscribe(ignored -> {}, failure -> finishStop(), this::finishStop);
    }

    private synchronized void finishStop() {
        lanes.clear();
        scheduler.dispose();
        if (shutdownTelemetry != null) {
            shutdownTelemetry.finish();
            shutdownTelemetry = null;
        }
    }

    @Override
    public int getPhase() {
        return WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE + 1;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    synchronized int pendingSignals() {
        return lanes.stream().mapToInt(lane -> lane.pending().size()).sum();
    }

    synchronized long coalescedSignals() {
        return lanes.stream().mapToLong(lane -> lane.pending().coalescedCount()).sum();
    }

    private static boolean isRelay(RegisteredSweep sweep) {
        return sweep.definition().metadata().name().equals("outbox-relay");
    }

    private record Lane(
            PendingSweepQueue pending,
            Disposable.Swap source,
            Sinks.Many<RegisteredSweep> signals,
            Sinks.Empty<Void> drained) {}
}
