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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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
    private static final String STAGING_MODE = "CBT_STAGING_ADVERSARIAL";

    private Optional<DatabaseTarget> databaseTarget = Optional.empty();
    private String migratorPassword = UUID.randomUUID().toString();
    private String apiPassword = UUID.randomUUID().toString();
    private ConnectionPool pool;
    private SecurityContextInitializer initializer;
    private ConnectionFactory migratorFactory;

    @BeforeAll
    void migrateDatabaseAndCreateSingleConnectionPool() throws Exception {
        boolean stagingMode = Boolean.parseBoolean(System.getenv(STAGING_MODE));
        DatabaseTarget target = stagingMode ? stagingDatabaseTarget() : localDatabaseTarget();
        databaseTarget = Optional.of(target);
        if (!stagingMode) {
            executeAsClusterOwner(readResource("db/provisioning/V1__create_migration_role.sql"));
            executeAsClusterOwner(
                    "ALTER ROLE app_migrator PASSWORD '%s'".formatted(migratorPassword));
            MigrationApplication.run(
                    new String[] {
                        "--migrate-only",
                        "--cbt.migration.jdbc-url=" + target.ownerJdbcUrl(),
                        "--cbt.migration.username=app_migrator",
                        "--cbt.migration.classification=EXPAND",
                        "--cbt.database.roles.app-migrator.password=" + migratorPassword
                    });
            executeAsClusterOwner("ALTER ROLE app_api PASSWORD '%s'".formatted(apiPassword));
        }
        executeAsClusterOwner(
                """
                INSERT INTO platform.tenant_scope_probe (tenant_id, probe_id)
                VALUES
                    ('%s', '10000000-0000-0000-0000-000000000071'),
                    ('%s', '10000000-0000-0000-0000-000000000072')
                ON CONFLICT DO NOTHING
                """
                        .formatted(TENANT_A, TENANT_B));

        ConnectionFactory apiFactory = connectionFactory("app_api", apiPassword);
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
        migratorFactory = connectionFactory("app_migrator", migratorPassword);
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
                                connectionFactory("app_api", apiPassword),
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

    @Test
    void saturatedSmallPoolResetsEveryRecycledConnection() {
        ConnectionPool saturatedPool =
                new ConnectionPool(
                        ConnectionPoolConfiguration.builder(
                                        connectionFactory("app_api", apiPassword))
                                .name("arc-verify-024-saturation")
                                .initialSize(2)
                                .maxSize(2)
                                .maxIdleTime(Duration.ofMinutes(1))
                                .preRelease(SecurityContextInitializer::resetBeforeRelease)
                                .build());
        SecurityContextInitializer saturatedInitializer =
                new SecurityContextInitializer(saturatedPool);

        Mono<SaturationResult> result =
                Flux.range(0, 12)
                        .flatMap(
                                index -> {
                                    AssumableDatabaseRole role =
                                            index % 2 == 0
                                                    ? AssumableDatabaseRole.DELIVERY
                                                    : AssumableDatabaseRole.PEOPLE;
                                    TenantId tenantId = index % 2 == 0 ? TENANT_A : TENANT_B;
                                    return saturatedInitializer
                                            .inTenantTransaction(
                                                    role,
                                                    tenantId,
                                                    connection ->
                                                            queryContext(connection)
                                                                    .delayElement(
                                                                            Duration.ofMillis(40)))
                                            .map(
                                                    snapshot ->
                                                            new SaturationObservation(
                                                                    role, tenantId, snapshot));
                                },
                                12)
                        .collectList()
                        .flatMap(
                                observations ->
                                        Flux.range(0, 2)
                                                .flatMap(
                                                        ignored ->
                                                                withConnection(
                                                                        saturatedPool,
                                                                        connection ->
                                                                                queryReleasedContext(
                                                                                                connection)
                                                                                        .delayElement(
                                                                                                Duration
                                                                                                        .ofMillis(
                                                                                                                40))),
                                                        2)
                                                .collectList()
                                                .map(
                                                        releasedContexts ->
                                                                new SaturationResult(
                                                                        observations,
                                                                        releasedContexts)));

        try {
            StepVerifier.create(result)
                    .assertNext(AdversarialConnectionReuseIntegrationTest::assertSaturationResult)
                    .verifyComplete();
        } finally {
            StepVerifier.create(saturatedPool.disposeLater()).verifyComplete();
        }
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

    private Mono<ReleasedContext> queryReleasedContext(Connection connection) {
        return Flux.from(
                        connection
                                .createStatement(
                                        """
                                        SELECT pg_backend_pid() AS backend_pid,
                                               current_role AS database_role,
                                               coalesce(
                                                   nullif(current_setting('app.tenant_id', true), ''),
                                                   ''
                                               ) = '' AS tenant_setting_is_absent
                                        """)
                                .execute())
                .flatMap(
                        queryResult ->
                                queryResult.map(
                                        (row, metadata) ->
                                                new ReleasedContext(
                                                        Objects.requireNonNull(
                                                                row.get(
                                                                        "backend_pid",
                                                                        Integer.class)),
                                                        Objects.requireNonNull(
                                                                row.get(
                                                                        "database_role",
                                                                        String.class)),
                                                        Boolean.TRUE.equals(
                                                                row.get(
                                                                        "tenant_setting_is_absent",
                                                                        Boolean.class)))))
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

    private static void assertSaturationResult(SaturationResult result) {
        assertThat(result.observations())
                .hasSize(12)
                .allSatisfy(
                        observation ->
                                assertContext(
                                        observation.snapshot(),
                                        observation.role(),
                                        observation.tenantId()));

        Set<Integer> transactionBackendPids =
                result.observations().stream()
                        .map(observation -> observation.snapshot().backendPid())
                        .collect(Collectors.toUnmodifiableSet());
        assertThat(transactionBackendPids).hasSize(2);
        transactionBackendPids.forEach(
                backendPid ->
                        assertThat(
                                        result.observations().stream()
                                                .filter(
                                                        observation ->
                                                                observation.snapshot().backendPid()
                                                                        == backendPid)
                                                .count())
                                .as("transactions served by backend %s", backendPid)
                                .isGreaterThan(1));

        assertThat(result.releasedContexts())
                .hasSize(2)
                .allSatisfy(
                        releasedContext -> {
                            assertThat(releasedContext.databaseRole()).isEqualTo("app_api");
                            assertThat(releasedContext.tenantSettingIsAbsent()).isTrue();
                        });
        assertThat(
                        result.releasedContexts().stream()
                                .map(ReleasedContext::backendPid)
                                .collect(Collectors.toUnmodifiableSet()))
                .containsExactlyInAnyOrderElementsOf(transactionBackendPids);
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
        DatabaseTarget target = databaseTarget();
        ConnectionFactoryOptions options =
                ConnectionFactoryOptions.builder()
                        .option(DRIVER, "postgresql")
                        .option(HOST, target.host())
                        .option(PORT, target.port())
                        .option(DATABASE, target.databaseName())
                        .option(USER, username)
                        .option(PASSWORD, password)
                        .build();
        return ConnectionFactories.get(options);
    }

    private void executeAsClusterOwner(String sql) throws SQLException {
        DatabaseTarget target = databaseTarget();
        try (var connection =
                        DriverManager.getConnection(
                                target.ownerJdbcUrl(),
                                target.ownerUsername(),
                                target.ownerPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private DatabaseTarget localDatabaseTarget() {
        PostgreSQLContainer postgres = PostgreSqlTestContainer.instance();
        postgres.start();
        return new DatabaseTarget(
                postgres.getHost(),
                postgres.getMappedPort(5432),
                postgres.getDatabaseName(),
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
    }

    private DatabaseTarget stagingDatabaseTarget() {
        migratorPassword = requiredEnvironment("CBT_STAGING_MIGRATOR_PASSWORD");
        apiPassword = requiredEnvironment("CBT_STAGING_API_PASSWORD");
        return new DatabaseTarget(
                requiredEnvironment("CBT_STAGING_DATABASE_HOST"),
                Integer.parseInt(requiredEnvironment("CBT_STAGING_DATABASE_PORT")),
                requiredEnvironment("CBT_STAGING_DATABASE_NAME"),
                requiredEnvironment("CBT_STAGING_JDBC_URL"),
                requiredEnvironment("CBT_STAGING_OWNER_USERNAME"),
                requiredEnvironment("CBT_STAGING_OWNER_PASSWORD"));
    }

    private DatabaseTarget databaseTarget() {
        return databaseTarget.orElseThrow(
                () -> new IllegalStateException("Database is not configured"));
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required staging setting is missing: " + name);
        }
        return value;
    }

    private String readResource(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record ContextSnapshot(int backendPid, String databaseRole, String tenantId) {}

    private record SaturationObservation(
            AssumableDatabaseRole role, TenantId tenantId, ContextSnapshot snapshot) {}

    private record ReleasedContext(
            int backendPid, String databaseRole, boolean tenantSettingIsAbsent) {}

    private record SaturationResult(
            List<SaturationObservation> observations, List<ReleasedContext> releasedContexts) {}

    private record DatabaseTarget(
            String host,
            int port,
            String databaseName,
            String ownerJdbcUrl,
            String ownerUsername,
            String ownerPassword) {}

    private static final class ApplicationFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
