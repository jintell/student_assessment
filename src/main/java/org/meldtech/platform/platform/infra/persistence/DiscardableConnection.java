package org.meldtech.platform.platform.infra.persistence;

import io.r2dbc.spi.Connection;
import reactor.core.publisher.Mono;

/** A pooled connection lease that can be invalidated instead of returned to the pool. */
public interface DiscardableConnection extends Connection {

    Mono<Void> discard();
}
