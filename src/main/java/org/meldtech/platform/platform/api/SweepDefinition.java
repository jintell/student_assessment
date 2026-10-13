package org.meldtech.platform.platform.api;

import reactor.core.publisher.Mono;

/** A resumable sweep whose work is performed in bounded, independently committed batches. */
public interface SweepDefinition {

    SweepMetadata metadata();

    /**
     * Returns deferred work for one batch. Check the stop signal before beginning work; a stop
     * requested during a batch permits that batch to finish but prohibits starting another.
     * Completion must include transaction commit or rollback. Cancellation must roll back
     * unfinished work and release its resources. Implementations must not subscribe to detached
     * work, and must recover progress from durable state on the next invocation.
     */
    Mono<SweepBatchResult> runBatch(SweepStopSignal stopSignal);
}
