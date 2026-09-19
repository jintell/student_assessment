package org.meldtech.platform.migration.backfill;

import java.time.Duration;
import reactor.core.publisher.Mono;

public final class RateLimitedBackfillThrottle implements BackfillThrottle {

    @Override
    public Mono<Void> afterCommittedBatch(int rows, int rowsPerSecond) {
        if (rows <= 0) {
            return Mono.empty();
        }
        long delayMillis = Math.max(1, Math.ceilDiv(rows * 1_000L, rowsPerSecond));
        return Mono.delay(Duration.ofMillis(delayMillis)).then();
    }
}
