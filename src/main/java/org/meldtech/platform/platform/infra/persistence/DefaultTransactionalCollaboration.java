package org.meldtech.platform.platform.infra.persistence;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Statement;
import java.util.Objects;
import java.util.function.Function;
import org.meldtech.platform.platform.api.AuditStatementKind;
import org.meldtech.platform.platform.api.TransactionalCollaboration;
import org.meldtech.platform.platform.api.TransactionalConnection;
import org.meldtech.platform.shared.api.AtomicCrossModuleFlow;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;

final class DefaultTransactionalCollaboration implements TransactionalCollaboration {

    private static final AtomicCrossModuleFlow FLOW = AtomicCrossModuleFlow.EXAM_ENTRY;

    private final SecurityContextInitializer connectionFactory;

    DefaultTransactionalCollaboration(SecurityContextInitializer connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    @Override
    public <T> Mono<T> inExamEntryTransaction(
            TenantId tenantId, Function<TransactionalConnection, Mono<T>> work) {
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
        R2dbcTransactionalConnection handle = new R2dbcTransactionalConnection(connection);
        return Mono.defer(() -> work.apply(handle))
                .doOnSuccess(ignored -> handle.verifyReadyForCompletion())
                .contextWrite(context -> context.put(TransactionalConnection.class, handle));
    }

    private static final class R2dbcTransactionalConnection implements TransactionalConnection {

        private final Connection connection;
        private FinalizationState finalizationState = FinalizationState.BUSINESS_SQL;

        private R2dbcTransactionalConnection(Connection connection) {
            this.connection = Objects.requireNonNull(connection, "connection");
        }

        @Override
        public synchronized Statement createStatement(String sql) {
            if (finalizationState != FinalizationState.BUSINESS_SQL) {
                throw new IllegalStateException(
                        "Business SQL is forbidden after audit finalization begins");
            }
            return connection.createStatement(sql);
        }

        @Override
        public synchronized void beginAuditFinalization() {
            if (finalizationState != FinalizationState.BUSINESS_SQL
                    && finalizationState != FinalizationState.FINALIZED) {
                throw new IllegalStateException("An audit append protocol is already in progress");
            }
            finalizationState = FinalizationState.LOCK_REQUIRED;
        }

        @Override
        public synchronized Statement createAuditStatement(AuditStatementKind kind, String sql) {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(sql, "sql");
            return switch (kind) {
                case LOCK_PREDECESSOR -> {
                    requireState(FinalizationState.LOCK_REQUIRED, kind);
                    finalizationState = FinalizationState.APPEND_REQUIRED;
                    yield connection.createStatement(sql);
                }
                case APPEND_AND_ADVANCE -> {
                    requireState(FinalizationState.APPEND_REQUIRED, kind);
                    finalizationState = FinalizationState.FINALIZED;
                    yield connection.createStatement(sql);
                }
            };
        }

        @Override
        public synchronized void verifyReadyForCompletion() {
            if (finalizationState != FinalizationState.BUSINESS_SQL
                    && finalizationState != FinalizationState.FINALIZED) {
                throw new IllegalStateException("Audit append protocol is incomplete");
            }
        }

        private void requireState(FinalizationState expected, AuditStatementKind attempted) {
            if (finalizationState != expected) {
                throw new IllegalStateException(
                        "Audit statement "
                                + attempted
                                + " is invalid while connection is "
                                + finalizationState);
            }
        }

        private enum FinalizationState {
            BUSINESS_SQL,
            LOCK_REQUIRED,
            APPEND_REQUIRED,
            FINALIZED
        }
    }
}
