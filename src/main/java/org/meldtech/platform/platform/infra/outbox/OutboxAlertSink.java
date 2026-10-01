package org.meldtech.platform.platform.infra.outbox;

import reactor.core.publisher.Mono;

@FunctionalInterface
interface OutboxAlertSink {

    Mono<Void> publicationFailed(String eventId, PublicationFailureReason reason);
}
