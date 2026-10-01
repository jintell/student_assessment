package org.meldtech.platform.platform.infra.outbox;

import reactor.core.publisher.Mono;

interface DeadLetterPublisher {

    Mono<Void> deadLetter(ConsumerEnvelope envelope, DeadLetterReason reason);

    enum DeadLetterReason {
        UNHANDLED_EVENT_VERSION,
        POISON_PAYLOAD
    }
}
