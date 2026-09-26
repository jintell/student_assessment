package org.meldtech.platform.platform.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.TransactionalConnection;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DefaultTransactionalCollaborationTest {

    @Test
    void ownsOneConnectionAndExposesItsHandleThroughReactorContext() {
        List<String> events = new ArrayList<>();
        DefaultTransactionalCollaboration collaboration = collaboration(events);

        StepVerifier.create(
                        collaboration.inExamEntryTransaction(
                                tenantId(),
                                handle ->
                                        TransactionalConnection.current()
                                                .doOnNext(
                                                        current ->
                                                                assertThat(current)
                                                                        .isSameAs(handle))
                                                .thenReturn("done")))
                .expectNext("done")
                .verifyComplete();

        assertThat(events)
                .containsExactly(
                        "CREATE",
                        "BEGIN",
                        "SET LOCAL ROLE app_txn_examentry",
                        "SET LOCAL app.tenant_id = '10000000-0000-0000-0000-000000000001'",
                        "SET LOCAL search_path = pg_catalog",
                        "COMMIT",
                        "RESET ROLE",
                        "RESET ALL",
                        "CLOSE");
    }

    @Test
    void rejectsNestedCollaborationScopes() {
        DefaultTransactionalCollaboration collaboration = collaboration(new ArrayList<>());

        StepVerifier.create(
                        collaboration
                                .inExamEntryTransaction(tenantId(), handle -> Mono.just("unused"))
                                .contextWrite(
                                        context ->
                                                context.put(
                                                        TransactionalConnection.class,
                                                        (TransactionalConnection)
                                                                sql -> statementProxy())))
                .expectErrorMessage("Nested synchronous collaboration transactions are forbidden")
                .verify();
    }

    private DefaultTransactionalCollaboration collaboration(List<String> events) {
        Connection connection = recordingConnection(events);
        ConnectionFactory delegate =
                new ConnectionFactory() {
                    @Override
                    public Mono<? extends Connection> create() {
                        events.add("CREATE");
                        return Mono.just(connection);
                    }

                    @Override
                    public ConnectionFactoryMetadata getMetadata() {
                        return () -> "test-database";
                    }
                };
        return new DefaultTransactionalCollaboration(new SecurityContextInitializer(delegate));
    }

    private Connection recordingConnection(List<String> events) {
        return (Connection)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, arguments) -> {
                            switch (method.getName()) {
                                case "beginTransaction" -> events.add("BEGIN");
                                case "commitTransaction" -> events.add("COMMIT");
                                case "rollbackTransaction" -> events.add("ROLLBACK");
                                case "close" -> events.add("CLOSE");
                                case "createStatement" -> {
                                    events.add((String) arguments[0]);
                                    return statementProxy();
                                }
                                case "toString" -> {
                                    return "recording-connection";
                                }
                                default ->
                                        throw new UnsupportedOperationException(method.getName());
                            }
                            return Mono.empty();
                        });
    }

    private static Statement statementProxy() {
        Result result =
                (Result)
                        Proxy.newProxyInstance(
                                DefaultTransactionalCollaborationTest.class.getClassLoader(),
                                new Class<?>[] {Result.class},
                                (proxy, method, arguments) -> {
                                    if (method.getName().equals("getRowsUpdated")) {
                                        return Mono.just(0L);
                                    }
                                    throw new UnsupportedOperationException(method.getName());
                                });
        return (Statement)
                Proxy.newProxyInstance(
                        DefaultTransactionalCollaborationTest.class.getClassLoader(),
                        new Class<?>[] {Statement.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("execute")) {
                                return Mono.just(result);
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }

    private TenantId tenantId() {
        return TenantId.parse("10000000-0000-0000-0000-000000000001");
    }
}
