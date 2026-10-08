package org.meldtech.platform.audit.application;

import java.time.Instant;
import java.util.List;
import reactor.core.publisher.Mono;

public interface AuditHoldRepository {

    /** Returns true only for the first suspension of this hold/policy state. */
    Mono<Boolean> suspend(
            DispositionRequest request, List<ActiveLegalHold> holds, Instant detectedAt);

    Mono<ResumedDisposition> release(DispositionRequest request, Instant releasedAt);
}
