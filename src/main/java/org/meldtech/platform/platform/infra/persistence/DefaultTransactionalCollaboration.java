package org.meldtech.platform.platform.infra.persistence;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Statement;
import java.util.Objects;
import java.util.function.Function;
import org.meldtech.platform.platform.api.TransactionalCollaboration;
import org.meldtech.platform.platform.api.TransactionalConnection;
import org.meldtech.platform.shared.api.AtomicCrossModuleFlow;
import org.meldtech.platform.shared.api.RequestTenantId;
import reactor.core.publisher.Mono;

final class DefaultTransactionalCollaboration implements TransactionalCollaboration {

    private static final AtomicCrossModuleFlow FLOW = AtomicCrossModuleFlow.EXAM_ENTRY;

    private final SecurityContextInitializer connectionFactory;

    DefaultTransactionalCollaboration(SecurityContextInitializer connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    @Override
    public <T> Mono<T> inExamEntryTransaction(
            RequestTenantId tenantId, Function<TransactionalConnection, Mono<T>> work) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(work, "work");
        return Mono.deferContextual(
                context -> {
                    if (context.hasKey(TransactionalConnection.class)) {
                        return Mono.error(
                                new IllegalStateException(
                                        "Nested synchronous collaboration transactions are forbidden"));
                    }
                    return connectionFactory.inTenantTransaction(
                            roleFor(FLOW), tenantId, connection -> invoke(work, connection));
                });
    }

    private static AssumableDatabaseRole roleFor(AtomicCrossModuleFlow flow) {
        if (!flow.compositeRole().equals(AssumableDatabaseRole.EXAM_ENTRY.roleName())) {
            throw new IllegalStateException(
                    "ADR-023 composite role does not match the assumable-role enumeration");
        }
        return AssumableDatabaseRole.EXAM_ENTRY;
    }

    private static <T> Mono<T> invoke(
            Function<TransactionalConnection, Mono<T>> work, Connection connection) {
        TransactionalConnection handle = new R2dbcTransactionalConnection(connection);
        return Mono.defer(() -> work.apply(handle))
                .contextWrite(context -> context.put(TransactionalConnection.class, handle));
    }

    private record R2dbcTransactionalConnection(Connection connection)
            implements TransactionalConnection {

        private R2dbcTransactionalConnection {
            Objects.requireNonNull(connection, "connection");
        }

        @Override
        public Statement createStatement(String sql) {
            return connection.createStatement(sql);
        }
    }
}
