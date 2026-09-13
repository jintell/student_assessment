package org.meldtech.platform.platform.infra.persistence;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.r2dbc.spi.Batch;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.ConnectionMetadata;
import io.r2dbc.spi.IsolationLevel;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import io.r2dbc.spi.TransactionDefinition;
import io.r2dbc.spi.ValidationDepth;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.meldtech.platform.shared.api.PlatformOperation;
import org.meldtech.platform.shared.api.RequestCarrier;
import org.meldtech.platform.shared.api.RequestTenantId;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

final class SecurityContextInitializer implements ConnectionFactory {

    private final ConnectionFactory delegate;
    private final DatabaseContextMetrics metrics;

    SecurityContextInitializer(ConnectionFactory delegate) {
        this(delegate, new DatabaseContextMetrics(Metrics.globalRegistry));
    }

    SecurityContextInitializer(ConnectionFactory delegate, DatabaseContextMetrics metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public Publisher<? extends Connection> create() {
        return Mono.from(delegate.create())
                .map(connection -> new SecurityContextConnection(connection, metrics));
    }

    @Override
    public ConnectionFactoryMetadata getMetadata() {
        return delegate.getMetadata();
    }

    ConnectionFactory delegate() {
        return delegate;
    }

    static <T> Mono<T> withTenantScope(
            AssumableDatabaseRole role, RequestTenantId tenantId, Publisher<T> work) {
        DatabaseSecurityContext securityContext =
                new DatabaseSecurityContext(
                        Objects.requireNonNull(role, "role"),
                        "SET LOCAL app.tenant_id = '" + tenantId.value() + "'");
        return Mono.from(work)
                .contextWrite(
                        context -> context.put(DatabaseSecurityContext.class, securityContext));
    }

    static <T> Mono<T> withPlatformScope(
            AssumableDatabaseRole role,
            PlatformOperation operation,
            RequestCarrier carrier,
            Publisher<T> work) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(carrier, "carrier");
        if (carrier.tenantId().isPresent()) {
            return Mono.error(
                    new IllegalArgumentException(
                            "Tenant and platform database scopes are mutually exclusive"));
        }
        if (carrier.actor().filter(operation::permits).isEmpty()) {
            return Mono.error(
                    new SecurityException(
                            "Actor is not permitted for platform operation " + operation));
        }
        DatabaseSecurityContext securityContext =
                new DatabaseSecurityContext(
                        Objects.requireNonNull(role, "role"),
                        "SET LOCAL app.platform_scope = '" + operation.settingValue() + "'");
        return Mono.from(work)
                .contextWrite(
                        context -> context.put(DatabaseSecurityContext.class, securityContext));
    }

    <T> Mono<T> inTenantTransaction(
            AssumableDatabaseRole role,
            RequestTenantId tenantId,
            Function<Connection, ? extends Publisher<T>> work) {
        Objects.requireNonNull(work, "work");
        return withTenantScope(role, tenantId, managedTransaction(work));
    }

    <T> Mono<T> inPlatformTransaction(
            AssumableDatabaseRole role,
            PlatformOperation operation,
            RequestCarrier carrier,
            Function<Connection, ? extends Publisher<T>> work) {
        Objects.requireNonNull(work, "work");
        Mono<T> transaction = managedTransaction(work);
        return withPlatformScope(role, operation, carrier, transaction);
    }

    private <T> Mono<T> managedTransaction(Function<Connection, ? extends Publisher<T>> work) {
        return Mono.usingWhen(
                Mono.from(create()),
                connection ->
                        Mono.from(connection.beginTransaction())
                                .then(Mono.from(work.apply(connection))),
                connection ->
                        Mono.from(connection.commitTransaction())
                                .then(Mono.from(connection.close())),
                (connection, failure) ->
                        Mono.from(connection.rollbackTransaction())
                                .onErrorResume(
                                        cleanupFailure -> {
                                            failure.addSuppressed(cleanupFailure);
                                            return Mono.empty();
                                        })
                                .then(Mono.from(connection.close()))
                                .onErrorResume(
                                        cleanupFailure -> {
                                            failure.addSuppressed(cleanupFailure);
                                            return Mono.empty();
                                        }),
                connection ->
                        Mono.from(connection.rollbackTransaction())
                                .onErrorResume(ignored -> Mono.empty())
                                .then(Mono.from(connection.close()))
                                .onErrorResume(ignored -> Mono.empty()));
    }

    static Publisher<Void> resetBeforeRelease(Connection connection) {
        return executeRaw(connection, "RESET ROLE").then(executeRaw(connection, "RESET ALL"));
    }

    private static Mono<Void> executeRaw(Connection connection, String sql) {
        return Flux.from(connection.createStatement(sql).execute())
                .flatMap(Result::getRowsUpdated)
                .then();
    }

    private static final class SecurityContextConnection implements Connection {

        private final Connection delegate;
        private final DatabaseContextMetrics metrics;
        private final AtomicReference<State> state = new AtomicReference<>(State.CHECKED_OUT);
        private final AtomicBoolean cleanupStarted = new AtomicBoolean();

        private SecurityContextConnection(Connection delegate, DatabaseContextMetrics metrics) {
            this.delegate = delegate;
            this.metrics = metrics;
        }

        @Override
        public Publisher<Void> beginTransaction() {
            return begin(delegate.beginTransaction());
        }

        @Override
        public Publisher<Void> beginTransaction(TransactionDefinition definition) {
            return begin(delegate.beginTransaction(definition));
        }

        @Override
        public Publisher<Void> close() {
            return Mono.defer(this::cleanup);
        }

        @Override
        public Publisher<Void> commitTransaction() {
            requireReady("commit");
            return Mono.from(delegate.commitTransaction())
                    .doOnSuccess(ignored -> state.set(State.TRANSACTION_FINISHED));
        }

        @Override
        public Batch createBatch() {
            requireReady("batch");
            return new GuardedBatch(delegate.createBatch());
        }

        @Override
        public Publisher<Void> createSavepoint(String name) {
            return delegate.createSavepoint(name);
        }

        @Override
        public Statement createStatement(String sql) {
            requireReady("statement");
            rejectRoleMutation(sql);
            return delegate.createStatement(sql);
        }

        @Override
        public boolean isAutoCommit() {
            return delegate.isAutoCommit();
        }

        @Override
        public ConnectionMetadata getMetadata() {
            return delegate.getMetadata();
        }

        @Override
        public IsolationLevel getTransactionIsolationLevel() {
            return delegate.getTransactionIsolationLevel();
        }

        @Override
        public Publisher<Void> releaseSavepoint(String name) {
            return delegate.releaseSavepoint(name);
        }

        @Override
        public Publisher<Void> rollbackTransaction() {
            return Mono.from(delegate.rollbackTransaction())
                    .doOnSuccess(ignored -> state.set(State.TRANSACTION_FINISHED));
        }

        @Override
        public Publisher<Void> rollbackTransactionToSavepoint(String name) {
            return delegate.rollbackTransactionToSavepoint(name);
        }

        @Override
        public Publisher<Void> setAutoCommit(boolean autoCommit) {
            return delegate.setAutoCommit(autoCommit);
        }

        @Override
        public Publisher<Void> setLockWaitTimeout(Duration timeout) {
            return delegate.setLockWaitTimeout(timeout);
        }

        @Override
        public Publisher<Void> setStatementTimeout(Duration timeout) {
            return delegate.setStatementTimeout(timeout);
        }

        @Override
        public Publisher<Void> setTransactionIsolationLevel(IsolationLevel isolationLevel) {
            return delegate.setTransactionIsolationLevel(isolationLevel);
        }

        @Override
        public Publisher<Boolean> validate(ValidationDepth depth) {
            return delegate.validate(depth);
        }

        private Mono<Void> begin(Publisher<Void> beginTransaction) {
            return Mono.deferContextual(
                    contextView -> {
                        DatabaseSecurityContext securityContext =
                                contextView.getOrDefault(DatabaseSecurityContext.class, null);
                        if (securityContext == null) {
                            return Mono.error(
                                    new IllegalStateException(
                                            "R9_CONTEXT_NOT_FIRST: transaction has no database security context"));
                        }
                        if (!state.compareAndSet(State.CHECKED_OUT, State.TRANSACTION_OPEN)) {
                            return Mono.error(
                                    new IllegalStateException(
                                            "R10_ROLE_SWITCH: transaction context is already initialized"));
                        }
                        return Mono.from(beginTransaction)
                                .then(
                                        internalStatement(
                                                securityContext.role().roleName(),
                                                State.INSTALLING_ROLE,
                                                "SET LOCAL ROLE "
                                                        + securityContext.role().roleName()))
                                .then(
                                        internalStatement(
                                                securityContext.role().roleName(),
                                                State.INSTALLING_TENANT,
                                                securityContext.scopeStatement()))
                                .then(
                                        internalStatement(
                                                securityContext.role().roleName(),
                                                State.INSTALLING_SEARCH_PATH,
                                                securityContext.role().searchPathStatement()))
                                .doOnSuccess(ignored -> state.set(State.READY));
                    });
        }

        private Mono<Void> internalStatement(String role, State installingState, String sql) {
            return Mono.defer(
                            () -> {
                                state.set(installingState);
                                return Flux.from(delegate.createStatement(sql).execute())
                                        .flatMap(Result::getRowsUpdated)
                                        .then();
                            })
                    .doOnSuccess(
                            ignored -> {
                                if (installingState == State.INSTALLING_ROLE) {
                                    metrics.roleAssumption(role);
                                }
                            })
                    .doOnError(
                            ignored -> {
                                if (installingState == State.INSTALLING_ROLE
                                        || installingState == State.INSTALLING_TENANT) {
                                    metrics.contextInstallFailure(role);
                                }
                            })
                    .onErrorMap(
                            failure ->
                                    new IllegalStateException(
                                            "Database security context installation failed for "
                                                    + role,
                                            failure));
        }

        private Mono<Void> cleanup() {
            if (!cleanupStarted.compareAndSet(false, true)) {
                return Mono.empty();
            }
            State previous = state.getAndSet(State.RELEASING);
            AtomicReference<Throwable> cleanupFailure = new AtomicReference<>();
            Mono<Void> rollback =
                    isActive(previous)
                            ? attempt(delegate.rollbackTransaction(), cleanupFailure)
                            : Mono.empty();
            return rollback.then(attempt(executeRaw(delegate, "RESET ROLE"), cleanupFailure))
                    .then(attempt(executeRaw(delegate, "RESET ALL"), cleanupFailure))
                    .then(attempt(delegate.close(), cleanupFailure))
                    .then(
                            Mono.defer(
                                    () -> {
                                        state.set(State.CLOSED);
                                        Throwable failure = cleanupFailure.get();
                                        if (failure != null) {
                                            metrics.connectionResetFailure();
                                        }
                                        return failure == null ? Mono.empty() : Mono.error(failure);
                                    }));
        }

        private static Mono<Void> attempt(
                Publisher<Void> operation, AtomicReference<Throwable> firstFailure) {
            return Mono.from(operation)
                    .onErrorResume(
                            failure -> {
                                firstFailure.compareAndSet(null, failure);
                                return Mono.empty();
                            });
        }

        private static boolean isActive(State state) {
            return state == State.TRANSACTION_OPEN
                    || state == State.INSTALLING_ROLE
                    || state == State.INSTALLING_TENANT
                    || state == State.INSTALLING_SEARCH_PATH
                    || state == State.READY;
        }

        private void requireReady(String operation) {
            State current = state.get();
            if (current != State.READY) {
                metrics.contextMissing();
                throw new IllegalStateException(
                        "R9_CONTEXT_NOT_FIRST: transaction executed "
                                + operation
                                + " before the required SET LOCAL ROLE and scope statements");
            }
        }

        private static void rejectRoleMutation(String sql) {
            String normalized =
                    Objects.requireNonNull(sql, "sql").stripLeading().toUpperCase(Locale.ROOT);
            if (normalized.startsWith("SET ROLE")
                    || normalized.startsWith("SET LOCAL ROLE")
                    || normalized.startsWith("SET SESSION AUTHORIZATION")
                    || normalized.startsWith("RESET ROLE")) {
                throw new IllegalStateException(
                        "R10_ROLE_SWITCH: transaction attempted a role statement after initial role installation");
            }
        }

        private static final class GuardedBatch implements Batch {

            private final Batch delegate;

            private GuardedBatch(Batch delegate) {
                this.delegate = delegate;
            }

            @Override
            public Batch add(String sql) {
                rejectRoleMutation(sql);
                delegate.add(sql);
                return this;
            }

            @Override
            public Publisher<? extends Result> execute() {
                return delegate.execute();
            }
        }
    }

    private record DatabaseSecurityContext(AssumableDatabaseRole role, String scopeStatement) {}

    private enum State {
        CHECKED_OUT,
        TRANSACTION_OPEN,
        INSTALLING_ROLE,
        INSTALLING_TENANT,
        INSTALLING_SEARCH_PATH,
        READY,
        TRANSACTION_FINISHED,
        RELEASING,
        CLOSED
    }

    static final class DatabaseContextMetrics {

        private final MeterRegistry registry;
        private final Counter contextMissing;
        private final Counter connectionResetFailure;
        private final ConcurrentMap<String, Counter> installFailures = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Counter> roleAssumptions = new ConcurrentHashMap<>();

        DatabaseContextMetrics(MeterRegistry registry) {
            this.registry = Objects.requireNonNull(registry, "registry");
            contextMissing = registry.counter("db_context_missing_total");
            connectionResetFailure = registry.counter("db_connection_reset_failure_total");
        }

        void contextInstallFailure(String role) {
            counter(installFailures, "db_context_install_failure_total", role).increment();
        }

        void contextMissing() {
            contextMissing.increment();
        }

        void roleAssumption(String role) {
            counter(roleAssumptions, "db_role_assumption_total", role).increment();
        }

        void connectionResetFailure() {
            connectionResetFailure.increment();
        }

        private Counter counter(ConcurrentMap<String, Counter> counters, String name, String role) {
            return counters.computeIfAbsent(
                    role, key -> Counter.builder(name).tag("role", key).register(registry));
        }
    }
}
