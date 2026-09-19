package org.meldtech.platform.migration.backfill;

import reactor.core.publisher.Mono;

public interface BackfillOperationRegistry {

    Mono<BackfillRunResult> run(String operation);
}
