package org.meldtech.platform.platform.infra.outbox;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import org.meldtech.platform.platform.api.RoleScoped;
import org.meldtech.platform.platform.infra.persistence.DatabaseSecurityScopes;
import org.meldtech.platform.platform.infra.persistence.DiscardableConnection;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@RoleScoped("worker")
final class OutboxDatabasePrerequisites implements SmartInitializingSingleton {

    private static final String READINESS_SQL =
            """
            SELECT EXISTS (
                SELECT 1 FROM pg_catalog.pg_roles
                WHERE rolname = 'app_outbox_relay'
                  AND pg_catalog.pg_has_role(session_user, oid, 'SET')
            ) AND EXISTS (
                SELECT 1 FROM pg_catalog.pg_class relation
                JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                WHERE namespace.nspname = 'outbox'
                  AND relation.relname = 'outbox_event' AND relation.relkind = 'p'
            ) AS ready
            """;
    private final ConnectionFactory connections;

    OutboxDatabasePrerequisites(
            @Qualifier("workerConnectionFactory") ConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override
    public void afterSingletonsInstantiated() {
        var validation = validate().toFuture();
        try {
            validation.get();
        } catch (InterruptedException exception) {
            validation.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException exception) {
            throw unavailable();
        }
    }

    private Mono<Void> validate() {
        return Mono.usingWhen(
                        Mono.from(connections.create()),
                        connection ->
                                DatabaseSecurityScopes.inSweepCoordinationTransaction(
                                        connection,
                                        Flux.defer(
                                                        () ->
                                                                Flux.from(
                                                                        connection
                                                                                .createStatement(
                                                                                        READINESS_SQL)
                                                                                .execute()))
                                                .concatMap(
                                                        result ->
                                                                result.map(
                                                                        (row, metadata) ->
                                                                                Objects
                                                                                        .requireNonNull(
                                                                                                row
                                                                                                        .get(
                                                                                                                "ready",
                                                                                                                Boolean
                                                                                                                        .class))))
                                                .single()
                                                .filter(Boolean::booleanValue)
                                                .switchIfEmpty(Mono.error(unavailable()))),
                        connection -> Mono.from(connection.close()),
                        (connection, failure) -> discard(connection),
                        OutboxDatabasePrerequisites::discard)
                .timeout(Duration.ofSeconds(10))
                .then();
    }

    private static Mono<Void> discard(Connection connection) {
        return connection instanceof DiscardableConnection discardable
                ? discardable.discard()
                : Mono.from(connection.close());
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException(
                "OUTBOX_DATABASE_NOT_READY: complete the migration Job before starting workers");
    }
}
