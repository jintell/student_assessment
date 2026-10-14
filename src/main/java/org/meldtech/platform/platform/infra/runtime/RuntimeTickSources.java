package org.meldtech.platform.platform.infra.runtime;

import java.time.Duration;
import org.meldtech.platform.platform.api.SweepLock;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/** The application-owned periodic sources, including lock ownership checks. */
public final class RuntimeTickSources {
    private RuntimeTickSources() {}

    static Flux<Long> sweep(Duration interval, Scheduler scheduler) {
        return Flux.interval(interval, scheduler);
    }

    static Flux<Long> memory(Scheduler scheduler) {
        return Flux.interval(Duration.ZERO, Duration.ofSeconds(1), scheduler);
    }

    /** Cancels unfinished work when the coordination connection loses ownership. */
    public static <T> Mono<T> whileHeld(SweepLock.Handle handle, Mono<T> work) {
        Mono<T> ownershipLoss =
                Flux.interval(Duration.ofMillis(100))
                        .onBackpressureLatest()
                        .concatMap(ignored -> handle.assertHeld(), 1)
                        .then(Mono.never());
        return handle.assertHeld()
                .then(Mono.firstWithSignal(work, ownershipLoss))
                .flatMap(result -> handle.assertHeld().thenReturn(result));
    }
}
