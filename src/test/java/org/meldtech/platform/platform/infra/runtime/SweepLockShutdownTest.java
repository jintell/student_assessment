package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.SweepLock;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.support.GenericApplicationContext;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class SweepLockShutdownTest {

    @Test
    void lifecycleReleasesLocksAfterWebAndBrokerButBeforePoolDisposal() {
        var events = new ArrayList<String>();
        var shutdown = new SweepLockShutdown(Mono.fromRunnable(() -> events.add("pool")));
        var tick = shutdown.beginTick();
        int webPhase = WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE;
        try (var context = new GenericApplicationContext()) {
            context.registerBean("lockShutdown", SweepLockShutdown.class, () -> shutdown);
            context.registerBean(
                    "sweeps",
                    Phase.class,
                    () ->
                            new Phase(
                                    webPhase + 1,
                                    () -> {
                                        shutdown.beginDrain()
                                                .subscribe(
                                                        ignored -> {},
                                                        failure -> {},
                                                        () -> events.add("sweeps"));
                                        tick.bodyFinished();
                                        shutdown.release(
                                                        handle(
                                                                Mono.fromRunnable(
                                                                        () ->
                                                                                events.add(
                                                                                        "release"))),
                                                        false)
                                                .doFinally(ignored -> tick.finished())
                                                .subscribe();
                                    }));
            context.registerBean(
                    "web", Phase.class, () -> new Phase(webPhase, () -> events.add("web")));
            context.registerBean(
                    "broker",
                    Phase.class,
                    () -> new Phase(webPhase - 1, () -> events.add("broker")));
            context.refresh();
        }
        assertThat(events).containsExactly("sweeps", "web", "broker", "release", "pool");
        assertThat(shutdown.isRunning()).isFalse();
    }

    @Test
    void waitsForAsynchronousReleaseAndDisposesThePoolOnlyOnce() {
        var release = Sinks.<Void>empty();
        var pools = new AtomicInteger();
        var callbacks = new AtomicInteger();
        var shutdown = new SweepLockShutdown(Mono.fromRunnable(pools::incrementAndGet));
        var tick = shutdown.beginTick();
        shutdown.beginDrain();
        tick.bodyFinished();
        shutdown.release(handle(release.asMono()), false)
                .doFinally(ignored -> tick.finished())
                .subscribe();
        shutdown.stop(callbacks::incrementAndGet);
        shutdown.stop(callbacks::incrementAndGet);
        assertThat(pools).hasValue(0);
        assertThat(callbacks).hasValue(0);
        release.tryEmitEmpty();
        assertThat(pools).hasValue(1);
        assertThat(callbacks).hasValue(2);
    }

    @Test
    void failedHandlesDoNotWaitBehindTheCleanShutdownBarrier() {
        var releases = new AtomicInteger();
        var shutdown = new SweepLockShutdown(Mono.empty());
        var tick = shutdown.beginTick();
        shutdown.beginDrain();
        tick.bodyFinished();
        shutdown.release(handle(Mono.fromRunnable(releases::incrementAndGet)), true)
                .doFinally(ignored -> tick.finished())
                .subscribe();
        assertThat(releases).hasValue(1);
        shutdown.stop();
    }

    private static SweepLock.Handle handle(Mono<Void> release) {
        return new SweepLock.Handle() {
            @Override
            public Mono<Void> assertHeld() {
                return Mono.empty();
            }

            @Override
            public Mono<Void> close() {
                return release;
            }
        };
    }

    private static final class Phase implements SmartLifecycle {
        private final int phase;
        private final Runnable drain;
        private boolean running;

        private Phase(int phase, Runnable drain) {
            this.phase = phase;
            this.drain = drain;
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            drain.run();
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            return phase;
        }
    }
}
