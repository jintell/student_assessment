package org.meldtech.platform.platform.infra.runtime;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Tracks transaction cleanup that continues asynchronously after subscription cancellation. */
public final class TransactionCleanup {

    private final Sinks.Empty<Void> completed = Sinks.empty();
    private int transactions;
    private boolean awaiting;

    public synchronized void acquired() {
        transactions++;
    }

    public synchronized void released() {
        transactions--;
        completeIfDrained();
    }

    public synchronized Mono<Void> await() {
        awaiting = true;
        completeIfDrained();
        return completed.asMono();
    }

    private void completeIfDrained() {
        if (awaiting && transactions == 0) {
            completed.tryEmitEmpty();
        }
    }
}
