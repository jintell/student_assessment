package org.meldtech.platform.conformance.fixtures.r9;

import io.r2dbc.spi.ConnectionFactory;
import reactor.core.publisher.Mono;

final class Handler {

    private final ConnectionFactory connectionFactory;

    Handler(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    Mono<Void> handle() {
        return Mono.from(connectionFactory.create())
                .flatMap(connection -> Mono.from(connection.beginTransaction()));
    }
}
