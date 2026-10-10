package org.meldtech.platform.audit.application;

import java.time.Instant;
import java.util.List;
import reactor.core.publisher.Mono;

public interface AuditHoldRepository {

    /** Joins the active transaction and returns true only when the persisted hold set changes. */
    Mono<Boolean> suspend(
            DispositionRequest request, List<ActiveLegalHold> holds, Instant detectedAt);

    /** Joins the active transaction and returns the original persisted retention clock. */
    Mono<ResumedDisposition> release(DispositionRequest request, Instant releasedAt);
}
