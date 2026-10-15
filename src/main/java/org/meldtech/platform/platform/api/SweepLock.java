package org.meldtech.platform.platform.api;

import reactor.core.publisher.Mono;

/** Sole application-facing contract for sweep advisory-lock ownership. */
public interface SweepLock {

    /** Returns an empty publisher when another session already owns the sweep lock. */
    Mono<Handle> tryAcquire(String sweepName);

    interface Handle {

        /** Fails when the retained database session no longer owns the lock. */
        Mono<Void> assertHeld();

        /** Releases the lock and its retained connection. Safe to call more than once. */
        Mono<Void> close();
    }
}
