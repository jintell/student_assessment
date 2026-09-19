package org.meldtech.platform.migration.backfill;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface BackfillThrottle {

    Mono<Void> afterCommittedBatch(int rows, int rowsPerSecond);
}
