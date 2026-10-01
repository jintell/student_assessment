package org.meldtech.platform.platform.infra.outbox;

import org.meldtech.platform.outbox.api.DeadLetterRedrive;
import reactor.core.publisher.Mono;

interface RedriveEligibility {

    Mono<Boolean> isEligible(DeadLetterRedrive request);
}
