package org.meldtech.platform.platform.api;

import io.r2dbc.spi.Statement;
import reactor.core.publisher.Mono;

/** Opaque handle to the connection owned by a reviewed synchronous collaboration flow. */
public interface TransactionalConnection {

    Statement createStatement(String sql);

    static Mono<TransactionalConnection> current() {
        return Mono.deferContextual(
                context ->
                        context.hasKey(TransactionalConnection.class)
                                ? Mono.just(context.get(TransactionalConnection.class))
                                : Mono.error(
                                        new IllegalStateException(
                                                "No synchronous collaboration transaction is active")));
    }
}
