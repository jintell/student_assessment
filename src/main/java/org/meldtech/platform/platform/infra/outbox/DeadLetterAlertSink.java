package org.meldtech.platform.platform.infra.outbox;

import reactor.core.publisher.Mono;

@FunctionalInterface
interface DeadLetterAlertSink {

    Mono<Void> deadLettered(String eventId, DeadLetterPublisher.DeadLetterReason reason);
}
