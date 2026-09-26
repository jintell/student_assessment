package org.meldtech.platform.platform.infra.persistence;

import static io.r2dbc.spi.ConnectionFactoryOptions.DATABASE;
import static io.r2dbc.spi.ConnectionFactoryOptions.DRIVER;
import static io.r2dbc.spi.ConnectionFactoryOptions.HOST;
import static io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD;
import static io.r2dbc.spi.ConnectionFactoryOptions.PORT;
import static io.r2dbc.spi.ConnectionFactoryOptions.USER;
import static org.assertj.core.api.Assertions.assertThat;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.R2dbcException;
import io.r2dbc.spi.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.meldtech.platform.PostgreSqlTestContainer;
import org.meldtech.platform.migration.MigrationApplication;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdversarialConnectionReuseIntegrationTest {

    private static final TenantId TENANT_A = TenantId.parse("00000000-0000-0000-0000-000000000071");
    private static final TenantId TENANT_B = TenantId.parse("00000000-0000-0000-0000-000000000072");
    private static final String MIGRATOR_PASSWORD = UUID.randomUUID().toString();
    private static final String API_PASSWORD = UUID.randomUUID().toString();

    private PostgreSQLContainer postgres;
    private ConnectionPool pool;
    private SecurityContextInitializer initializer;
    private ConnectionFactory migratorFactory;

    @BeforeAll
    void migrateDatabaseAndCreateSingleConnectionPool() throws Exception {
        postgres = PostgreSqlTestContainer.instance();
        postgres.start();
        executeAsClusterOwner(readResource("db/provisioning/V1__create_migration_role.sql"));
        executeAsClusterOwner("ALTER ROLE app_migrator PASSWORD '%s'".formatted(MIGRATOR_PASSWORD));
        MigrationApplication.run(
                new String[] {
                    "--migrate-only",
                    "--cbt.migration.jdbc-url=" + postgres.getJdbcUrl(),
                    "--cbt.migration.username=app_migrator",
                    "--cbt.migration.classification=EXPAND",
                    "--cbt.database.roles.app-migrator.password=" + MIGRATOR_PASSWORD
                });
        executeAsClusterOwner("ALTER ROLE app_api PASSWORD '%s'".formatted(API_PASSWORD));
        executeAsClusterOwner(
                """
                INSERT INTO platform.tenant_scope_probe (tenant_id, probe_id)
                VALUES
                    ('%s', '10000000-0000-0000-0000-000000000071'),
                    ('%s', '10000000-0000-0000-0000-000000000072')
                ON CONFLICT DO NOTHING
                """
                        .formatted(TENANT_A, TENANT_B));

        ConnectionFactory apiFactory = connectionFactory("app_api", API_PASSWORD);
        pool =
                new ConnectionPool(
                        ConnectionPoolConfiguration.builder(apiFactory)
                                .name("arc-verify-024")
                                .initialSize(1)
                                .maxSize(1)
                                .maxIdleTime(Duration.ofMinutes(1))
                                .preRelease(SecurityContextInitializer::resetBeforeRelease)
                                .build());
        initializer = new SecurityContextInitializer(pool);
        migratorFactory = connectionFactory("app_migrator", MIGRATOR_PASSWORD);
    }

    @AfterAll
    void disposePool() {
        StepVerifier.create(pool.disposeLater()).verifyComplete();
    }

    @Test
    void pooledConnectionNeverRetainsTenantOrRoleAcrossTerminationPaths() {
        List<ContextSnapshot> snapshots = new ArrayList<>();

        verifySuccess(AssumableDatabaseRole.DELIVERY, TENANT_A, snapshots);
        verifyApplicationException(AssumableDatabaseRole.PEOPLE, TENANT_B, snapshots);
        verifySuccess(AssumableDatabaseRole.TENANCY, TENANT_A, snapshots);
        verifyCancellation(AssumableDatabaseRole.AUTHORING, TENANT_B, snapshots);
        verifySuccess(AssumableDatabaseRole.EXAM_ACCESS, TENANT_A, snapshots);
        verifyReadTimeout(AssumableDatabaseRole.GRADING, TENANT_A, snapshots);
        verifySuccess(AssumableDatabaseRole.RESULT, TENANT_B, snapshots);
        verifyStatementTimeout(AssumableDatabaseRole.NOTIFICATION, TENANT_B, snapshots);
        verifySuccess(AssumableDatabaseRole.IAM, TENANT_A, snapshots);

        assertThat(snapshots).hasSize(9);
        assertThat(snapshots)
                .extracting(ContextSnapshot::backendPid)
                .containsOnly(snapshots.getFirst().backendPid());
    }

    @Test
    void uninitialisedConnectionHasNoPrivilegeOrTenantSetting() {
        StepVerifier.create(withRawPooledConnection(this::queryCount))
                .expectErrorMatches(AdversarialConnectionReuseIntegrationTest::isSqlState42501)
                .verify();

        StepVerifier.create(withRawPooledConnection(this::queryTenantSettingIsAbsent))
                .expectNext(true)
                .verifyComplete();

        StepVerifier.create(
                        withConnection(
                                connectionFactory("app_api", API_PASSWORD),
                                this::queryTenantSetting))
                .expectErrorMatches(AdversarialConnectionReuseIntegrationTest::isSqlState42704)
                .verify();
    }

    @Test
    void forcedRlsRestrictsTheTableOwner() {
        Mono<Integer> visibleRows =
                Mono.usingWhen(
                        Mono.from(migratorFactory.create()),
                        connection ->
                                Mono.from(connection.beginTransaction())
                                        .then(
                                                execute(
                                                        connection,
                                                        "SET LOCAL app.tenant_id = '"
                                                                + TENANT_A
                                                                + "'"))
                                        .then(queryCount(connection)),
                        connection ->
                                Mono.from(connection.rollbackTransaction())
                                        .then(Mono.from(connection.close())),
                        (connection, failure) ->
                                Mono.from(connection.rollbackTransaction())
                                        .then(Mono.from(connection.close())),
                        connection ->
                                Mono.from(connection.rollbackTransaction())
                                        .then(Mono.from(connection.close())));

        StepVerifier.create(visibleRows).expectNext(1).verifyComplete();
    }

    private void verifySuccess(
            AssumableDatabaseRole role, TenantId tenantId, List<ContextSnapshot> snapshots) {
        StepVerifier.create(initializer.inTenantTransaction(role, tenantId, this::queryContext))
                .assertNext(
                        snapshot -> {
                            assertContext(snapshot, role, tenantId);
                            snapshots.add(snapshot);
                        })
                .verifyComplete();
    }

    private void verifyApplicationException(
            AssumableDatabaseRole role, TenantId tenantId, List<ContextSnapshot> snapshots) {
        StepVerifier.create(
                        initializer.inTenantTransaction(
                                role,
                                tenantId,
                                connection ->
                                        queryContext(connection)
                                                .doOnNext(
                                                        snapshot -> {
                                                            assertContext(snapshot, role, tenantId);
                                                            snapshots.add(snapshot);
                                                        })
                                                .then(Mono.error(new ApplicationFailure()))))
                .expectError(ApplicationFailure.class)
                .verify();
    }

    private void verifyCancellation(
            AssumableDatabaseRole role, TenantId tenantId, List<ContextSnapshot> snapshots) {
        CountDownLatch contextObserved = new CountDownLatch(1);
        StepVerifier.create(
                        initializer.inTenantTransaction(
                                role,
                                tenantId,
                                connection ->
                                        queryContext(connection)
                                                .doOnNext(
                                                        snapshot -> {
                                                            assertContext(snapshot, role, tenantId);
                                                            snapshots.add(snapshot);
                                                            contextObserved.countDown();
                                                        })
                                                .then(Mono.never())))
                .then(
                        () -> {
                            try {
                                assertThat(contextObserved.await(5, TimeUnit.SECONDS)).isTrue();
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(exception);
                            }
                        })
                .thenCancel()
                .verify();
    }

    private void verifyReadTimeout(
            AssumableDatabaseRole role, TenantId tenantId, List<ContextSnapshot> snapshots) {
        StepVerifier.create(
                        initializer.inTenantTransaction(
                                role,
                                tenantId,
                                connection ->
                                        queryContext(connection)
                                                .doOnNext(
                                                        snapshot -> {
                                                            assertContext(snapshot, role, tenantId);
                                                            snapshots.add(snapshot);
                                                        })
                                                .then(Mono.never().timeout(Duration.ofMillis(50)))))
                .expectError(java.util.concurrent.TimeoutException.class)
                .verify();
    }

    private void verifyStatementTimeout(
            AssumableDatabaseRole role, TenantId tenantId, List<ContextSnapshot> snapshots) {
        StepVerifier.create(
                        initializer.inTenantTransaction(
                                role,
                                tenantId,
                                connection ->
                                        queryContext(connection)
                                                .doOnNext(
                                                        snapshot -> {
                                                            assertContext(snapshot, role, tenantId);
                                                            snapshots.add(snapshot);
                                                        })
                                                .then(
                                                        execute(
                                                                connection,
                                                                "SET LOCAL statement_timeout = '25ms'"))
                                                .then(
                                                        execute(
                                                                connection,
                                                                "SELECT pg_sleep(0.25)"))))
                .expectErrorMatches(AdversarialConnectionReuseIntegrationTest::isSqlState57014)
                .verify();
    }

    private Mono<ContextSnapshot> queryContext(Connection connection) {
        return Flux.from(
                        connection
                                .createStatement(
                                        """
                                        SELECT pg_backend_pid() AS backend_pid,
                                               current_role AS database_role,
                                               current_setting('app.tenant_id', false) AS tenant_id
                                        """)
                                .execute())
                .flatMap(
                        result ->
                                result.map(
                                        (row, metadata) ->
                                                new ContextSnapshot(
                                                        row.get("backend_pid", Integer.class),
                                                        row.get("database_role", String.class),
                                                        row.get("tenant_id", String.class))))
                .single();
    }

    private Mono<Integer> queryCount(Connection connection) {
        return Flux.from(
                        connection
                                .createStatement(
                                        "SELECT count(*) AS visible_rows FROM platform.tenant_scope_probe")
                                .execute())
                .flatMap(
                        result ->
                                result.map(
                                        (row, metadata) ->
                                                row.get("visible_rows", Long.class).intValue()))
                .single();
    }

    private Mono<String> queryTenantSetting(Connection connection) {
        return Flux.from(
                        connection
                                .createStatement(
                                        "SELECT current_setting('app.tenant_id', false) AS tenant_id")
                                .execute())
                .flatMap(
                        result -> result.map((row, metadata) -> row.get("tenant_id", String.class)))
                .single();
    }

    private Mono<Boolean> queryTenantSettingIsAbsent(Connection connection) {
        return Flux.from(
                        connection
                                .createStatement(
                                        """
                                        SELECT coalesce(
                                                   nullif(current_setting('app.tenant_id', true), ''),
                                                   ''
                                               ) = '' AS tenant_setting_is_absent
                                        """)
                                .execute())
                .flatMap(
                        result ->
                                result.map(
                                        (row, metadata) ->
                                                row.get("tenant_setting_is_absent", Boolean.class)))
                .single();
    }

    private <T> Mono<T> withRawPooledConnection(
            java.util.function.Function<Connection, Mono<T>> work) {
        return withConnection(pool, work);
    }

    private <T> Mono<T> withConnection(
            ConnectionFactory connectionFactory,
            java.util.function.Function<Connection, Mono<T>> work) {
        return Mono.usingWhen(
                Mono.from(connectionFactory.create()),
                work,
                connection -> Mono.from(connection.close()));
    }

    private static Mono<Void> execute(Connection connection, String sql) {
        return Flux.from(connection.createStatement(sql).execute())
                .flatMap(Result::getRowsUpdated)
                .then();
    }

    private static void assertContext(
            ContextSnapshot snapshot, AssumableDatabaseRole role, TenantId tenantId) {
        assertThat(snapshot.databaseRole()).isEqualTo(role.roleName());
        assertThat(snapshot.tenantId()).isEqualTo(tenantId.toString());
    }

    private static boolean isSqlState42501(Throwable failure) {
        return failure instanceof R2dbcException exception
                && "42501".equals(exception.getSqlState());
    }

    private static boolean isSqlState42704(Throwable failure) {
        return failure instanceof R2dbcException exception
                && "42704".equals(exception.getSqlState());
    }

    private static boolean isSqlState57014(Throwable failure) {
        return failure instanceof R2dbcException exception
                && "57014".equals(exception.getSqlState());
    }

    private ConnectionFactory connectionFactory(String username, String password) {
        ConnectionFactoryOptions options =
                ConnectionFactoryOptions.builder()
                        .option(DRIVER, "postgresql")
                        .option(HOST, postgres.getHost())
                        .option(PORT, postgres.getMappedPort(5432))
                        .option(DATABASE, postgres.getDatabaseName())
                        .option(USER, username)
                        .option(PASSWORD, password)
                        .build();
        return ConnectionFactories.get(options);
    }

    private void executeAsClusterOwner(String sql) throws SQLException {
        try (var connection =
                        DriverManager.getConnection(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String readResource(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record ContextSnapshot(int backendPid, String databaseRole, String tenantId) {}

    private static final class ApplicationFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
