package org.meldtech.platform.migration.backfill;

import reactor.core.publisher.Mono;

public interface BackfillRepository<K> {

    Mono<BackfillCheckpoint<K>> loadOrCreate(BackfillDefinition definition);

    /** Updates eligible rows and advances the cursor atomically in one short transaction. */
    Mono<BackfillBatch<K>> processNextBatch(
            BackfillDefinition definition, BackfillCheckpoint<K> checkpoint);

    Mono<Boolean> hasEligibleRowsAtOrBelow(
            BackfillDefinition definition, BackfillCheckpoint<K> checkpoint);
}
