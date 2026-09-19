package org.meldtech.platform.migration.backfill;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface BackfillRunPermit {

    Mono<Boolean> mayProcessNextBatch();
}
