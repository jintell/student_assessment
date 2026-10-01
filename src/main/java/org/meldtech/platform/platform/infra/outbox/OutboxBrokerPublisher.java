package org.meldtech.platform.platform.infra.outbox;

import reactor.core.publisher.Mono;

interface OutboxBrokerPublisher {

    Mono<BrokerConfirmation> publish(ClaimedOutboxEvent event);

    record BrokerConfirmation(boolean acknowledged) {}
}
