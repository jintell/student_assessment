package org.meldtech.platform.platform.infra.runtime;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.meldtech.platform.platform.api.SweepLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Separates transaction drain from final handle and dedicated-pool disposal. */
public final class SweepLockShutdown implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(SweepLockShutdown.class);
    private final Sinks.Empty<Void> bodiesDrained = Sinks.empty();
    private final Sinks.Empty<Void> ticksDrained = Sinks.empty();
    private final Sinks.Empty<Void> releaseAllowed = Sinks.empty();
    private final Mono<Void> shutdown;
    private int bodies;
    private int ticks;
    private boolean draining;
    private volatile boolean running;

    public SweepLockShutdown(Mono<Void> disposePool) {
        shutdown =
                Mono.defer(
                                () -> {
                                    beginDrain();
                                    releaseAllowed.tryEmitEmpty();
                                    return ticksDrained.asMono().then(disposePool);
                                })
                        .timeout(Duration.ofSeconds(5))
                        .doFinally(ignored -> running = false)
                        .cache();
    }

    synchronized Tick beginTick() {
        if (draining) {
            throw new IllegalStateException("Sweep shutdown has begun");
        }
        bodies++;
        ticks++;
        return new Tick();
    }

    synchronized Mono<Void> beginDrain() {
        draining = true;
        signalDrained();
        return bodiesDrained.asMono();
    }

    Mono<Void> release(SweepLock.Handle handle, boolean failed) {
        return Mono.defer(
                () -> {
                    synchronized (this) {
                        return draining && !failed
                                ? releaseAllowed.asMono().then(Mono.defer(handle::close))
                                : Mono.defer(handle::close);
                    }
                });
    }

    private void signalDrained() {
        if (draining && bodies == 0) {
            bodiesDrained.tryEmitEmpty();
        }
        if (draining && ticks == 0) {
            ticksDrained.tryEmitEmpty();
        }
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public void stop(Runnable callback) {
        shutdown.subscribe(
                ignored -> {},
                failure -> {
                    LOGGER.error("sweep_lock_drain_failed");
                    callback.run();
                },
                callback);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE - 2;
    }

    final class Tick {
        private final AtomicBoolean bodyFinished = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();

        void bodyFinished() {
            synchronized (SweepLockShutdown.this) {
                if (bodyFinished.compareAndSet(false, true)) {
                    bodies--;
                    signalDrained();
                }
            }
        }

        void finished() {
            synchronized (SweepLockShutdown.this) {
                bodyFinished();
                if (finished.compareAndSet(false, true)) {
                    ticks--;
                    signalDrained();
                }
            }
        }
    }
}
