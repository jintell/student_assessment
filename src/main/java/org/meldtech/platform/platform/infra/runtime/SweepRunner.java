package org.meldtech.platform.platform.infra.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
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
public final class SweepRunner implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(SweepRunner.class);
    private final Supplier<List<RegisteredSweep>> registry;
    private final SweepLock locks;
    private final CorrelationIdGenerator correlationIds;
    private final ActorContextPropagation contexts;
    private final Scheduler scheduler;
    private final List<Lane> lanes = new ArrayList<>();
    private volatile boolean running;

    @Autowired
    public SweepRunner(
            SweepRegistryLoader registry,
            SweepLock locks,
            CorrelationIdGenerator correlationIds,
            ActorContextPropagation contexts) {
        this(
                registry::enabledSweeps,
                locks,
                correlationIds,
                contexts,
                Schedulers.newSingle("sweep-ticks"));
    }

    SweepRunner(
            Supplier<List<RegisteredSweep>> registry,
            SweepLock locks,
            CorrelationIdGenerator correlationIds,
            ActorContextPropagation contexts,
            Scheduler scheduler) {
        this.registry = registry;
        this.locks = locks;
        this.correlationIds = correlationIds;
        this.contexts = contexts;
        this.scheduler = scheduler;
    }

    @Override
    public synchronized void start() {
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
        var lane = new Lane(new PendingSweepQueue(sweeps.size()), Disposables.composite());
        lanes.add(lane);
        Sinks.Many<RegisteredSweep> signals =
                Sinks.many().unicast().onBackpressureBuffer(lane.pending());
        // These are the lane's two owned subscriptions, never detached batch subscriptions.
        lane.subscriptions()
                .add(
                        signals.asFlux()
                                .concatMap(this::runTick, 0)
                                .subscribe(ignored -> {}, failure -> sourceFailed()));
        lane.subscriptions()
                .add(
                        Flux.fromIterable(sweeps)
                                .flatMap(
                                        sweep ->
                                                Flux.interval(sweep.interval(), scheduler)
                                                        .onBackpressureLatest()
                                                        .map(ignored -> sweep),
                                        sweeps.size(),
                                        1)
                                .subscribe(
                                        sweep -> {
                                            Sinks.EmitResult result = signals.tryEmitNext(sweep);
                                            if (running && result.isFailure()) {
                                                sourceFailed();
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
                            var stopped = new AtomicBoolean();
                            SweepStopSignal stop = () -> !running || stopped.get();
                            ActorContext actor =
                                    ActorContext.platformSystem(
                                            definition.metadata().systemActor(),
                                            correlationIds.generate(),
                                            new SourceIp("::1"));
                            return Mono.usingWhen(
                                            locks.tryAcquire(definition.metadata().name()),
                                            handle ->
                                                    batch(definition, handle, stop)
                                                            .expand(
                                                                    result ->
                                                                            result.moreAvailable()
                                                                                            && !stop
                                                                                                    .isStopRequested()
                                                                                    ? batch(
                                                                                            definition,
                                                                                            handle,
                                                                                            stop)
                                                                                    : Mono.empty())
                                                            .then()
                                                            .timeout(
                                                                    definition
                                                                            .metadata()
                                                                            .maximumTickDuration(),
                                                                    scheduler),
                                            SweepLock.Handle::close,
                                            (handle, failure) -> handle.close(),
                                            SweepLock.Handle::close)
                                    .doFinally(ignored -> stopped.set(true))
                                    .contextWrite(
                                            context ->
                                                    contexts.write(context, actor)
                                                            .put(
                                                                    SweepMetadata.class,
                                                                    definition.metadata()));
                        })
                .onErrorResume(
                        failure -> {
                            LOGGER.warn(
                                    "sweep_tick_failed sweep_name={}",
                                    sweep.definition().metadata().name());
                            return Mono.empty();
                        });
    }

    private Mono<SweepBatchResult> batch(
            SweepDefinition definition, SweepLock.Handle handle, SweepStopSignal stop) {
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
                            .flatMap(result -> handle.assertHeld().thenReturn(result));
                });
    }

    private void sourceFailed() {
        LOGGER.error("sweep_signal_source_failed action=stop_runner");
        stop();
    }

    @Override
    public synchronized void stop() {
        running = false;
        for (Lane lane : lanes) {
            lane.subscriptions().dispose();
            lane.pending().clear();
        }
        lanes.clear();
        scheduler.dispose();
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

    private record Lane(PendingSweepQueue pending, Disposable.Composite subscriptions) {}
}
