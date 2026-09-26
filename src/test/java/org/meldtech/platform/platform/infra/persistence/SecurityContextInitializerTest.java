package org.meldtech.platform.platform.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.api.PlatformOperation;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SecurityContextInitializerTest {

    @Test
    void decoratesEveryCreatedConnectionAndPreservesMetadata() {
        Connection connection = connectionProxy();
        ConnectionFactoryMetadata metadata = () -> "test-database";
        ConnectionFactory delegate = factory(connection, metadata);
        SecurityContextInitializer initializer = new SecurityContextInitializer(delegate);

        assertThat(initializer.getMetadata()).isSameAs(metadata);
        StepVerifier.create(initializer.create())
                .assertNext(created -> assertThat(created).isNotSameAs(connection))
                .verifyComplete();
    }

    @Test
    void installsRoleTenantAndSearchPathBeforeTransactionBecomesReady() {
        List<String> events = new ArrayList<>();
        Connection connection = recordingConnection(events);
        SecurityContextInitializer initializer =
                new SecurityContextInitializer(factory(connection, () -> "test-database"));
        TenantId tenantId = TenantId.parse("10000000-0000-0000-0000-000000000001");

        Mono<Void> transaction =
                Mono.from(initializer.create())
                        .flatMap(created -> Mono.from(created.beginTransaction()));

        StepVerifier.create(
                        SecurityContextInitializer.withTenantScope(
                                AssumableDatabaseRole.DELIVERY, tenantId, transaction))
                .verifyComplete();

        assertThat(events)
                .containsExactly(
                        "BEGIN",
                        "SET LOCAL ROLE app_delivery",
                        "SET LOCAL app.tenant_id = '10000000-0000-0000-0000-000000000001'",
                        "SET LOCAL search_path = pg_catalog, delivery");
    }

    @Test
    void defersApplicationWorkUntilContextInstallationCompletes() {
        List<String> events = new ArrayList<>();

        StepVerifier.create(
                        initializer(events)
                                .inTenantTransaction(
                                        AssumableDatabaseRole.DELIVERY,
                                        tenantId(),
                                        connection ->
                                                Flux.from(
                                                                connection
                                                                        .createStatement("SELECT 1")
                                                                        .execute())
                                                        .then(Mono.just("done"))))
                .expectNext("done")
                .verifyComplete();

        assertThat(events)
                .containsSubsequence(
                        "SET LOCAL ROLE app_delivery",
                        "SET LOCAL app.tenant_id = '10000000-0000-0000-0000-000000000001'",
                        "SET LOCAL search_path = pg_catalog, delivery",
                        "SELECT 1",
                        "COMMIT");
    }

    @Test
    void refusesCallerStatementsBeforeContextInstallation() {
        Connection connection = recordingConnection(new ArrayList<>());
        SecurityContextInitializer initializer =
                new SecurityContextInitializer(factory(connection, () -> "test-database"));

        StepVerifier.create(initializer.create())
                .assertNext(
                        created ->
                                assertThatThrownBy(() -> created.createStatement("SELECT 1"))
                                        .isInstanceOf(IllegalStateException.class)
                                        .hasMessageContaining("R9_CONTEXT_NOT_FIRST"))
                .verifyComplete();
    }

    @Test
    void resetsAfterSuccessfulCompletion() {
        List<String> events = runTransaction(connection -> Mono.just("done"));

        assertThat(events).endsWith("COMMIT", "RESET ROLE", "RESET ALL", "CLOSE");
    }

    @Test
    void rollsBackAndResetsAfterApplicationError() {
        List<String> events = new ArrayList<>();
        SecurityContextInitializer initializer = initializer(events);

        StepVerifier.create(
                        initializer.inTenantTransaction(
                                AssumableDatabaseRole.DELIVERY,
                                tenantId(),
                                connection -> Mono.error(new IllegalStateException("application"))))
                .expectErrorMessage("application")
                .verify();

        assertThat(events).endsWith("ROLLBACK", "RESET ROLE", "RESET ALL", "CLOSE");
    }

    @Test
    void rollsBackAndResetsAfterCancellation() {
        List<String> events = new ArrayList<>();
        SecurityContextInitializer initializer = initializer(events);

        StepVerifier.create(
                        initializer.inTenantTransaction(
                                AssumableDatabaseRole.DELIVERY,
                                tenantId(),
                                connection -> Mono.never()))
                .thenAwait(Duration.ofMillis(10))
                .thenCancel()
                .verify();

        assertThat(events).endsWith("ROLLBACK", "RESET ROLE", "RESET ALL", "CLOSE");
    }

    @Test
    void resetsAfterReadTimeout() {
        List<String> events = new ArrayList<>();
        SecurityContextInitializer initializer = initializer(events);

        StepVerifier.create(
                        initializer.inTenantTransaction(
                                AssumableDatabaseRole.DELIVERY,
                                tenantId(),
                                connection -> Mono.never().timeout(Duration.ofMillis(5))))
                .expectError(java.util.concurrent.TimeoutException.class)
                .verify();

        assertThat(events).endsWith("ROLLBACK", "RESET ROLE", "RESET ALL", "CLOSE");
    }

    @Test
    void resetsAfterStatementTimeoutError() {
        List<String> events = new ArrayList<>();
        SecurityContextInitializer initializer = initializer(events);

        StepVerifier.create(
                        initializer.inTenantTransaction(
                                AssumableDatabaseRole.DELIVERY,
                                tenantId(),
                                connection -> Mono.error(new StatementTimeoutException())))
                .expectError(StatementTimeoutException.class)
                .verify();

        assertThat(events).endsWith("ROLLBACK", "RESET ROLE", "RESET ALL", "CLOSE");
    }

    @Test
    void installsOnlyTheEnumeratedPlatformScopeForAnAuthorizedSystemActor() {
        List<String> events = new ArrayList<>();
        ActorContext actor =
                ActorContext.platformSystem(
                        SystemActor.RETENTION_ENGINE, correlationId(), sourceIp());

        StepVerifier.create(
                        initializer(events)
                                .inPlatformTransaction(
                                        AssumableDatabaseRole.TENANCY,
                                        PlatformOperation.RETENTION_SWEEP,
                                        actor,
                                        connection -> Mono.just("done")))
                .expectNext("done")
                .verifyComplete();

        assertThat(events)
                .containsSubsequence(
                        "SET LOCAL ROLE app_tenancy",
                        "SET LOCAL app.platform_scope = 'retention_sweep'",
                        "SET LOCAL search_path = pg_catalog, tenancy")
                .noneMatch(event -> event.contains("app.tenant_id"));
    }

    @Test
    void rejectsTenantContextAndUnapprovedActorsForPlatformScope() {
        ActorContext tenantCarrier =
                ActorContext.tenantSystem(
                        SystemActor.RETENTION_ENGINE, tenantId(), correlationId(), sourceIp());
        ActorContext unapprovedActor =
                ActorContext.platformWorkforce(
                        new ActorId("unapproved-operator"), correlationId(), sourceIp());

        StepVerifier.create(
                        initializer(new ArrayList<>())
                                .inPlatformTransaction(
                                        AssumableDatabaseRole.TENANCY,
                                        PlatformOperation.RETENTION_SWEEP,
                                        tenantCarrier,
                                        connection -> Mono.just("unused")))
                .expectErrorMessage("Tenant and platform database scopes are mutually exclusive")
                .verify();
        StepVerifier.create(
                        initializer(new ArrayList<>())
                                .inPlatformTransaction(
                                        AssumableDatabaseRole.TENANCY,
                                        PlatformOperation.RETENTION_SWEEP,
                                        unapprovedActor,
                                        connection -> Mono.just("unused")))
                .expectError(SecurityException.class)
                .verify();
    }

    @Test
    void refusesARoleSwitchAfterInitialContextInstallation() {
        List<String> events = new ArrayList<>();

        StepVerifier.create(
                        initializer(events)
                                .inTenantTransaction(
                                        AssumableDatabaseRole.DELIVERY,
                                        tenantId(),
                                        connection ->
                                                Mono.fromCallable(
                                                        () ->
                                                                connection.createStatement(
                                                                        "SET LOCAL ROLE app_people"))))
                .expectErrorMatches(
                        failure ->
                                failure instanceof IllegalStateException
                                        && String.valueOf(failure.getMessage())
                                                .contains("R10_ROLE_SWITCH"))
                .verify();

        assertThat(events).endsWith("ROLLBACK", "RESET ROLE", "RESET ALL", "CLOSE");
    }

    @Test
    void recordsContextRefusalAndSuccessfulRoleAssumptionWithoutSensitiveTags() {
        MeterRegistry registry = new SimpleMeterRegistry();
        SecurityContextInitializer initializer =
                initializer(new ArrayList<>(), registry, Optional.empty());

        StepVerifier.create(initializer.create())
                .assertNext(
                        connection ->
                                assertThatThrownBy(
                                                () ->
                                                        connection.createStatement(
                                                                "SELECT tenant_id FROM delivery.attempt"))
                                        .isInstanceOf(IllegalStateException.class))
                .verifyComplete();
        StepVerifier.create(
                        initializer.inTenantTransaction(
                                AssumableDatabaseRole.DELIVERY,
                                tenantId(),
                                connection -> Mono.just("done")))
                .expectNext("done")
                .verifyComplete();

        assertThat(registry.get("db_context_missing_total").counter().count()).isEqualTo(1.0);
        assertThat(
                        registry.get("db_role_assumption_total")
                                .tag("role", "app_delivery")
                                .counter()
                                .count())
                .isEqualTo(1.0);
        assertThat(registry.getMeters())
                .allMatch(
                        meter ->
                                meter.getId().getTags().stream()
                                        .allMatch(tag -> tag.getKey().equals("role")));
    }

    @Test
    void recordsContextInstallationFailures() {
        MeterRegistry installRegistry = new SimpleMeterRegistry();
        SecurityContextInitializer installFailure =
                initializer(
                        new ArrayList<>(),
                        installRegistry,
                        Optional.of("SET LOCAL ROLE app_delivery"));

        StepVerifier.create(
                        installFailure.inTenantTransaction(
                                AssumableDatabaseRole.DELIVERY,
                                tenantId(),
                                connection -> Mono.just("unused")))
                .expectError(IllegalStateException.class)
                .verify();
        assertThat(
                        installRegistry
                                .get("db_context_install_failure_total")
                                .tag("role", "app_delivery")
                                .counter()
                                .count())
                .isEqualTo(1.0);
    }

    @Test
    void refusesSuccessfulReleaseWhenConnectionResetFails() {
        MeterRegistry resetRegistry = new SimpleMeterRegistry();
        List<String> events = new ArrayList<>();
        SecurityContextInitializer resetFailure =
                initializer(events, resetRegistry, Optional.of("RESET ROLE"));

        StepVerifier.create(
                        resetFailure.inTenantTransaction(
                                AssumableDatabaseRole.DELIVERY,
                                tenantId(),
                                connection -> Mono.just("done")))
                .expectError(RuntimeException.class)
                .verify();

        assertThat(events).endsWith("COMMIT", "RESET ROLE", "RESET ALL", "CLOSE");
        assertThat(resetRegistry.get("db_connection_reset_failure_total").counter().count())
                .isEqualTo(1.0);
    }

    private List<String> runTransaction(
            java.util.function.Function<Connection, Mono<String>> work) {
        List<String> events = new ArrayList<>();
        StepVerifier.create(
                        initializer(events)
                                .inTenantTransaction(
                                        AssumableDatabaseRole.DELIVERY, tenantId(), work))
                .expectNext("done")
                .verifyComplete();
        return events;
    }

    private SecurityContextInitializer initializer(List<String> events) {
        return initializer(events, new SimpleMeterRegistry(), Optional.empty());
    }

    private SecurityContextInitializer initializer(
            List<String> events, MeterRegistry registry, Optional<String> failingSql) {
        return new SecurityContextInitializer(
                factory(recordingConnection(events, failingSql), () -> "test-database"),
                new SecurityContextInitializer.DatabaseContextMetrics(registry));
    }

    private TenantId tenantId() {
        return TenantId.parse("10000000-0000-0000-0000-000000000001");
    }

    private CorrelationId correlationId() {
        return CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV");
    }

    private SourceIp sourceIp() {
        return SourceIp.parse("127.0.0.1");
    }

    private ConnectionFactory factory(Connection connection, ConnectionFactoryMetadata metadata) {
        return new ConnectionFactory() {
            @Override
            public Mono<? extends Connection> create() {
                return Mono.just(connection);
            }

            @Override
            public ConnectionFactoryMetadata getMetadata() {
                return metadata;
            }
        };
    }

    private Connection connectionProxy() {
        return (Connection)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("toString")) {
                                return "connection";
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }

    private Connection recordingConnection(List<String> events) {
        return recordingConnection(events, Optional.empty());
    }

    private Connection recordingConnection(List<String> events, Optional<String> failingSql) {
        return (Connection)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("beginTransaction")) {
                                events.add("BEGIN");
                                return Mono.empty();
                            }
                            if (method.getName().equals("createStatement")) {
                                String sql = (String) arguments[0];
                                events.add(sql);
                                if (failingSql.filter(sql::equals).isPresent()) {
                                    return failingStatementProxy();
                                }
                                return statementProxy();
                            }
                            if (method.getName().equals("commitTransaction")) {
                                events.add("COMMIT");
                                return Mono.empty();
                            }
                            if (method.getName().equals("rollbackTransaction")) {
                                events.add("ROLLBACK");
                                return Mono.empty();
                            }
                            if (method.getName().equals("close")) {
                                events.add("CLOSE");
                                return Mono.empty();
                            }
                            if (method.getName().equals("toString")) {
                                return "recording-connection";
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }

    private Statement statementProxy() {
        Result result =
                (Result)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {Result.class},
                                (proxy, method, arguments) -> {
                                    if (method.getName().equals("getRowsUpdated")) {
                                        return Mono.just(0L);
                                    }
                                    throw new UnsupportedOperationException(method.getName());
                                });
        return (Statement)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {Statement.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("execute")) {
                                return Mono.just(result);
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }

    private Statement failingStatementProxy() {
        return (Statement)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {Statement.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("execute")) {
                                return Mono.error(
                                        new RuntimeException("database operation failed"));
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }

    private static final class StatementTimeoutException extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
