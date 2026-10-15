package org.meldtech.platform.platform.infra.runtime;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import org.meldtech.platform.platform.api.RoleScoped;
import org.meldtech.platform.platform.api.SweepLock;
import org.meldtech.platform.platform.api.SweepLockLostException;
import org.meldtech.platform.platform.infra.persistence.DatabaseSecurityScopes;
import org.meldtech.platform.platform.infra.persistence.DiscardableConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Retains a lock-only transaction so transaction poolers pin its PostgreSQL backend. */
@Component
@RoleScoped("worker")
@ConditionalOnProperty(name = "cbt.runtime.sweep-lock.mode", havingValue = "transaction")
public final class TransactionSweepLock implements SweepLock {

    static final String ACQUIRE_SQL =
            """
            SELECT registry.lock_key,
                   pg_try_advisory_xact_lock(registry.lock_key) AS acquired
            FROM platform.sweep_registry AS registry
            WHERE registry.sweep_name = $1
            """;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(2);
    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionSweepLock.class);
    private final ConnectionFactory connections;
    private final SessionSweepLock.Commands commands;

    @Autowired
    public TransactionSweepLock(
            @Qualifier("sweepLockConnectionFactory") ConnectionFactory connections) {
        this(connections, new SessionSweepLock.PostgresCommands(ACQUIRE_SQL));
    }

    TransactionSweepLock(ConnectionFactory connections, SessionSweepLock.Commands commands) {
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
                            Mono.from(connections.create())
                                    .timeout(COMMAND_TIMEOUT)
                                    .map(Lease::new),
                            lease ->
                                    DatabaseSecurityScopes.beginSweepCoordinationTransaction(
                                                    lease.connection)
                                            .then(
                                                    Mono.defer(
                                                            () ->
                                                                    commands.tryAcquire(
                                                                            lease.connection,
                                                                            sweepName)))
                                            .timeout(COMMAND_TIMEOUT)
                                            .flatMap(
                                                    acquisition -> {
                                                        if (!acquisition.acquired()) {
                                                            return Mono.empty();
                                                        }
                                                        lease.retained = true;
                                                        return Mono.<Handle>just(
                                                                new TransactionHandle(
                                                                        lease.connection,
                                                                        acquisition.lockKey(),
                                                                        sweepName));
                                                    }),
                            Lease::closeUnlessRetained,
                            (lease, failure) -> lease.discard(),
                            Lease::discard);
                });
    }

    private static Mono<Void> discardConnection(Connection connection) {
        return connection instanceof DiscardableConnection discardable
                ? discardable.discard()
                : Mono.from(connection.close());
    }

    private static Mono<Void> rollbackAndClose(Connection connection, String sweepName) {
        return Mono.defer(() -> Mono.from(connection.rollbackTransaction()))
                .timeout(COMMAND_TIMEOUT)
                .then(Mono.defer(() -> Mono.from(connection.close())).timeout(COMMAND_TIMEOUT))
                .onErrorResume(
                        failure -> {
                            LOGGER.warn(
                                    "sweep_lock_release_failed "
                                            + SweepLogPolicy.SWEEP_NAME
                                            + "={} "
                                            + SweepLogPolicy.ACTION
                                            + "=discard_connection",
                                    SweepLogPolicy.safeSweepName(sweepName));
                            return discardConnection(connection)
                                    .onErrorResume(
                                            cleanupFailure -> {
                                                failure.addSuppressed(cleanupFailure);
                                                return Mono.empty();
                                            })
                                    .then(Mono.error(failure));
                        });
    }

    private static final class Lease {
        private final Connection connection;
        private boolean retained;

        private Lease(Connection connection) {
            this.connection = connection;
        }

        private Mono<Void> closeUnlessRetained() {
            return retained ? Mono.empty() : rollbackAndClose(connection, "contended");
        }

        private Mono<Void> discard() {
            return discardConnection(connection);
        }
    }

    private final class TransactionHandle implements Handle {
        private final Connection connection;
        private final long lockKey;
        private final String sweepName;
        private final Mono<Void> release;
        private Mono<Void> tail = Mono.empty();
        private boolean closing;

        private TransactionHandle(Connection connection, long lockKey, String sweepName) {
            this.connection = connection;
            this.lockKey = lockKey;
            this.sweepName = sweepName;
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
            return append(
                    () ->
                            commands.isHeld(connection, lockKey)
                                    .timeout(COMMAND_TIMEOUT)
                                    .flatMap(
                                            held ->
                                                    held
                                                            ? Mono.<Void>empty()
                                                            : Mono.error(
                                                                    new SweepLockLostException()))
                                    .onErrorMap(
                                            failure ->
                                                    failure instanceof SweepLockLostException
                                                            ? failure
                                                            : new SweepLockLostException(failure))
                                    .onErrorResume(
                                            failure ->
                                                    discardConnection(connection)
                                                            .then(Mono.error(failure))));
        }

        private synchronized Mono<Void> enqueueRelease() {
            closing = true;
            return append(() -> rollbackAndClose(connection, sweepName));
        }

        private Mono<Void> append(Supplier<Mono<Void>> command) {
            Sinks.Empty<Void> complete = Sinks.empty();
            Mono<Void> queued =
                    tail.then(Mono.defer(command)).doFinally(ignored -> complete.tryEmitEmpty());
            tail = complete.asMono();
            return queued;
        }
    }
}
