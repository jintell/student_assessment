package org.meldtech.platform.platform.infra.runtime;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import java.util.Objects;
import java.util.function.Supplier;
import org.meldtech.platform.platform.api.RoleScoped;
import org.meldtech.platform.platform.api.SweepLock;
import org.meldtech.platform.platform.api.SweepLockLostException;
import org.meldtech.platform.platform.infra.persistence.DatabaseSecurityScopes;
import org.meldtech.platform.platform.infra.persistence.DiscardableConnection;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Session-level PostgreSQL advisory lock backed by the dedicated worker lock pool. */
@Component
@RoleScoped("worker")
@ConditionalOnProperty(
        name = "cbt.runtime.sweep-lock.mode",
        havingValue = "session",
        matchIfMissing = true)
public final class SessionSweepLock implements SweepLock {

    static final String ACQUIRE_SQL =
            """
            SELECT registry.lock_key,
                   pg_try_advisory_lock(registry.lock_key) AS acquired
            FROM platform.sweep_registry AS registry
            WHERE registry.sweep_name = $1
            """;
    static final String HELD_SQL =
            """
            SELECT EXISTS (
                SELECT 1
                FROM pg_catalog.pg_locks AS lock
                WHERE lock.locktype = 'advisory'
                  AND lock.pid = pg_catalog.pg_backend_pid()
                  AND lock.granted
                  AND lock.objsubid = 1
                  AND lock.classid = (($1 >> 32) & 4294967295)::oid
                  AND lock.objid = ($1 & 4294967295)::oid
            ) AS held
            """;
    static final String RELEASE_SQL = "SELECT pg_advisory_unlock($1) AS released";

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionSweepLock.class);

    private final ConnectionFactory connections;
    private final Commands commands;

    @Autowired
    public SessionSweepLock(
            @Qualifier("sweepLockConnectionFactory") ConnectionFactory connections) {
        this(connections, new PostgresCommands(ACQUIRE_SQL));
    }

    SessionSweepLock(ConnectionFactory connections, Commands commands) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    @Override
    public Mono<Handle> tryAcquire(String sweepName) {
        return Mono.defer(
                () -> {
                    if (sweepName == null || sweepName.isBlank()) {
                        return Mono.error(
                                new IllegalArgumentException("Sweep name must not be blank"));
                    }
                    return Mono.usingWhen(
                            Mono.from(connections.create()).map(ConnectionLease::new),
                            lease -> acquire(lease, sweepName),
                            ConnectionLease::closeUnlessRetained,
                            ConnectionLease::discardUnlessRetained,
                            ConnectionLease::discardUnlessRetained);
                });
    }

    private Mono<Handle> acquire(ConnectionLease lease, String sweepName) {
        Connection connection = lease.connection();
        return inCoordinationTransaction(
                        connection, Mono.defer(() -> commands.tryAcquire(connection, sweepName)))
                .flatMap(
                        acquisition -> {
                            if (!acquisition.acquired()) {
                                return Mono.empty();
                            }
                            lease.retain();
                            return Mono.<Handle>just(
                                    new SessionHandle(
                                            sweepName,
                                            acquisition.lockKey(),
                                            connection,
                                            commands));
                        });
    }

    static <T> Mono<T> inCoordinationTransaction(Connection connection, Publisher<T> operation) {
        return DatabaseSecurityScopes.inSweepCoordinationTransaction(connection, operation);
    }

    private static Mono<Void> discard(Connection connection, Throwable primaryFailure) {
        Mono<Void> cleanup =
                connection instanceof DiscardableConnection discardable
                        ? discardable.discard()
                        : Mono.from(connection.close());
        return cleanup.onErrorResume(
                cleanupFailure -> {
                    primaryFailure.addSuppressed(cleanupFailure);
                    return Mono.empty();
                });
    }

    private static final class ConnectionLease {

        private final Connection connection;
        private boolean retained;

        private ConnectionLease(Connection connection) {
            this.connection = connection;
        }

        private Connection connection() {
            return connection;
        }

        private void retain() {
            retained = true;
        }

        private Mono<Void> closeUnlessRetained() {
            return retained ? Mono.empty() : Mono.from(connection.close());
        }

        private Mono<Void> discardUnlessRetained() {
            if (retained) {
                return Mono.empty();
            }
            return connection instanceof DiscardableConnection discardable
                    ? discardable.discard()
                    : Mono.from(connection.close());
        }

        private Mono<Void> discardUnlessRetained(Throwable primaryFailure) {
            return discardUnlessRetained()
                    .onErrorResume(
                            cleanupFailure -> {
                                primaryFailure.addSuppressed(cleanupFailure);
                                return Mono.empty();
                            });
        }
    }

    static final class SessionHandle implements Handle {

        private final String sweepName;
        private final long lockKey;
        private final Connection connection;
        private final Commands commands;
        private final Mono<Void> release;
        private Mono<Void> commandTail = Mono.empty();
        private boolean closing;

        SessionHandle(String sweepName, long lockKey, Connection connection, Commands commands) {
            this.sweepName = sweepName;
            this.lockKey = lockKey;
            this.connection = connection;
            this.commands = commands;
            release = Mono.defer(this::enqueueRelease).cache();
        }

        @Override
        public Mono<Void> assertHeld() {
            return Mono.defer(this::enqueueCheck);
        }

        @Override
        public Mono<Void> close() {
            return release;
        }

        private synchronized Mono<Void> enqueueCheck() {
            if (closing) {
                return Mono.error(
                        new IllegalStateException(
                                "Sweep advisory lock handle is closing or closed"));
            }
            return append(this::checkHeld);
        }

        private synchronized Mono<Void> enqueueRelease() {
            closing = true;
            return append(this::releaseOnce);
        }

        private Mono<Void> append(Supplier<Mono<Void>> command) {
            Sinks.Empty<Void> completion = Sinks.empty();
            Mono<Void> queued =
                    commandTail
                            .then(Mono.defer(command))
                            .doFinally(ignored -> completion.tryEmitEmpty());
            commandTail = completion.asMono();
            return queued;
        }

        private Mono<Void> checkHeld() {
            return inCoordinationTransaction(
                            connection, Mono.defer(() -> commands.isHeld(connection, lockKey)))
                    .flatMap(
                            held ->
                                    held
                                            ? Mono.<Void>empty()
                                            : Mono.error(new SweepLockLostException()))
                    .onErrorMap(
                            failure ->
                                    failure instanceof SweepLockLostException
                                            ? failure
                                            : new SweepLockLostException(failure));
        }

        private Mono<Void> releaseOnce() {
            return inCoordinationTransaction(
                            connection, Mono.defer(() -> commands.release(connection, lockKey)))
                    .flatMap(
                            released ->
                                    released
                                            ? Mono.from(connection.close())
                                            : Mono.error(
                                                    new IllegalStateException(
                                                            "PostgreSQL reported that the sweep lock was not held")))
                    .onErrorResume(
                            failure -> {
                                LOGGER.warn(
                                        "sweep_lock_release_failed sweep_name={} action=discard_connection",
                                        sweepName,
                                        failure);
                                return discard(connection, failure).then(Mono.error(failure));
                            });
        }
    }

    interface Commands {

        Mono<Acquisition> tryAcquire(Connection connection, String sweepName);

        Mono<Boolean> isHeld(Connection connection, long lockKey);

        Mono<Boolean> release(Connection connection, long lockKey);
    }

    record Acquisition(long lockKey, boolean acquired) {}

    static final class PostgresCommands implements Commands {

        private final String acquireSql;

        PostgresCommands(String acquireSql) {
            this.acquireSql = acquireSql;
        }

        @Override
        public Mono<Acquisition> tryAcquire(Connection connection, String sweepName) {
            return rows(
                            connection.createStatement(acquireSql).bind(0, sweepName).execute(),
                            (row, metadata) ->
                                    new Acquisition(
                                            Objects.requireNonNull(
                                                    row.get("lock_key", Long.class),
                                                    "Registry lock key"),
                                            Boolean.TRUE.equals(
                                                    row.get("acquired", Boolean.class))))
                    .single()
                    .onErrorMap(
                            java.util.NoSuchElementException.class,
                            ignored ->
                                    new IllegalArgumentException(
                                            "Unknown sweep registry name: " + sweepName));
        }

        @Override
        public Mono<Boolean> isHeld(Connection connection, long lockKey) {
            return booleanResult(connection, HELD_SQL, "held", lockKey);
        }

        @Override
        public Mono<Boolean> release(Connection connection, long lockKey) {
            return booleanResult(connection, RELEASE_SQL, "released", lockKey);
        }

        private static Mono<Boolean> booleanResult(
                Connection connection, String sql, String column, long lockKey) {
            return rows(
                            connection.createStatement(sql).bind(0, lockKey).execute(),
                            (row, metadata) -> Boolean.TRUE.equals(row.get(column, Boolean.class)))
                    .single();
        }

        private static <T> Flux<T> rows(
                Publisher<? extends Result> results,
                java.util.function.BiFunction<
                                io.r2dbc.spi.Row, io.r2dbc.spi.RowMetadata, ? extends T>
                        mapper) {
            return Flux.from(results).flatMap(result -> result.map(mapper));
        }
    }
}
