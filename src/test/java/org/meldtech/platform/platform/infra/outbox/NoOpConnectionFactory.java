package org.meldtech.platform.platform.infra.outbox;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import reactor.core.publisher.Mono;

final class NoOpConnectionFactory implements ConnectionFactory {

    @Override
    public Mono<? extends Connection> create() {
        return Mono.error(new UnsupportedOperationException("No database access expected"));
    }

    @Override
    public ConnectionFactoryMetadata getMetadata() {
        return () -> "PostgreSQL";
    }
}
