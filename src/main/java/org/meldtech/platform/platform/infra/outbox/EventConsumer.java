package org.meldtech.platform.platform.infra.outbox;

import java.util.Set;
import reactor.core.publisher.Mono;

interface EventConsumer {

    Set<EventType> handledVersions();

    Mono<ProcessedEventOutcome> handle(ConsumerEnvelope envelope);
}
