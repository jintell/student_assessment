package org.meldtech.platform.platform.infra.persistence;

import io.r2dbc.spi.ConnectionFactory;
import org.meldtech.platform.platform.api.TransactionalCollaboration;

/** Gives audit integration tests the production transaction and finalization guards. */
public final class AuditTransactionTestSupport {
    private AuditTransactionTestSupport() {}

    public static TransactionalCollaboration collaboration(ConnectionFactory connections) {
        return new DefaultTransactionalCollaboration(new SecurityContextInitializer(connections));
    }
}
