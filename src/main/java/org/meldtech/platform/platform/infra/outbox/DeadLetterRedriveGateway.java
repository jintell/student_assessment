package org.meldtech.platform.platform.infra.outbox;

import org.meldtech.platform.outbox.api.DeadLetterRedrive;
import reactor.core.publisher.Mono;

interface DeadLetterRedriveGateway {

    Mono<Void> republishAndAcknowledge(DeadLetterRedrive request);
}
