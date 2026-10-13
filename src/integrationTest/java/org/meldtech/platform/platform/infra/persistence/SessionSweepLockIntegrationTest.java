package org.meldtech.platform.platform.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.ConnectionFactory;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.meldtech.platform.PostgreSqlTestContainer;
import org.meldtech.platform.migration.MigrationApplication;
import org.meldtech.platform.platform.api.SweepDataModule;
import org.meldtech.platform.platform.api.SweepLock;
import org.meldtech.platform.platform.api.SweepMetadata;
import org.meldtech.platform.platform.infra.observability.DatabaseQueryTelemetry;
import org.meldtech.platform.platform.infra.observability.TracingConnectionFactory;
import org.meldtech.platform.platform.infra.runtime.SessionSweepLock;
import org.meldtech.platform.platform.infra.runtime.SweepRegistryLoader;
import org.meldtech.platform.platform.infra.runtime.TransactionSweepLock;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionSweepLockIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private PostgreSQLContainer postgres;
    private ConnectionPool physicalPool;
    private SessionSweepLock lock;
    private TransactionSweepLock transactionLock;
    private SweepRegistryLoader registryLoader;
    private ConnectionPool workerPool;
    private DefaultSweepTransactions sweepTransactions;

    @BeforeAll
    void prepareDatabaseAndLockPool() throws Exception {
        postgres = PostgreSqlTestContainer.instance();
        postgres.start();
        String migratorPassword = UUID.randomUUID().toString();
        String workerPassword = UUID.randomUUID().toString();
        executeAsClusterOwner(readResource("db/provisioning/V1__create_migration_role.sql"));
        executeAsClusterOwner("ALTER ROLE app_migrator PASSWORD '%s'".formatted(migratorPassword));
        MigrationApplication.run(
                new String[] {
                    "--migrate-only",
                    "--cbt.migration.jdbc-url=" + postgres.getJdbcUrl(),
                    "--cbt.migration.username=app_migrator",
                    "--cbt.migration.classification=EXPAND",
                    "--cbt.database.roles.app-migrator.password=" + migratorPassword
                });
        executeAsClusterOwner("ALTER ROLE app_worker PASSWORD '%s'".formatted(workerPassword));

        String url =
                "r2dbc:postgresql://%s:%d/%s"
                        .formatted(
                                postgres.getHost(),
                                postgres.getMappedPort(5432),
                                postgres.getDatabaseName());
        var properties =
                new WorkloadDatabaseProperties(
                        Map.of(
                                "worker", pool(url, workerPassword, 4),
                                "grading", pool(url, workerPassword, 4),
                                "sweep-lock", pool(url, workerPassword, 2)));
        ConnectionFactory connectionFactory =
                new WorkloadConnectionPoolConfiguration()
                        .sweepLockConnectionFactory(
                                properties,
                                metrics,
                                OpenTelemetry.noop(),
                                DatabaseQueryTelemetry.NOOP,
                                java.time.Instant::now);
        physicalPool = physicalPool(connectionFactory);
        lock = new SessionSweepLock(connectionFactory);
        transactionLock = new TransactionSweepLock(connectionFactory);
        registryLoader = new SweepRegistryLoader(connectionFactory, java.util.List.of());
        ConnectionFactory workerFactory =
                new WorkloadConnectionPoolConfiguration()
                        .workerConnectionFactory(
                                properties,
                                metrics,
                                OpenTelemetry.noop(),
                                DatabaseQueryTelemetry.NOOP,
                                java.time.Instant::now);
        workerPool = physicalPool(workerFactory);
        sweepTransactions =
                new DefaultSweepTransactions(
                        workerFactory, (context, actor) -> context.put(ActorContext.class, actor));
    }

    @AfterAll
    void closePool() {
        if (physicalPool != null) {
            physicalPool.dispose();
        }
        if (workerPool != null) {
            workerPool.dispose();
        }
        metrics.close();
    }

    @Test
    void sweepBusinessTransactionInstallsRoleAndStrictPlatformMarkerOnTheWorkerPool() {
        String identitySql =
                "SELECT current_user || ':' || current_setting('app.platform_scope', false) AS identity";
        ActorContext actor =
                ActorContext.platformSystem(
                        SystemActor.AUTO_SUBMIT_SWEEPER,
                        new CorrelationId("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                        new SourceIp("::1"));
        var declaration =
                new SweepMetadata(
                        "auto-submit",
                        "delivery",
                        "FEAT-DLV-004",
                        SystemActor.AUTO_SUBMIT_SWEEPER,
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(4),
                        Duration.ofSeconds(30));
        StepVerifier.create(
                        sweepTransactions
                                .inPlatformTransaction(
                                        SweepDataModule.DELIVERY,
                                        connection ->
                                                Flux.from(
                                                                connection
                                                                        .createStatement(
                                                                                identitySql)
                                                                        .execute())
                                                        .concatMap(
                                                                result ->
                                                                        result.map(
                                                                                (row, metadata) ->
                                                                                        row.get(
                                                                                                "identity",
                                                                                                String
                                                                                                        .class)))
                                                        .single())
                                .contextWrite(
                                        Context.of(
                                                ActorContext.class,
                                                actor,
                                                SweepMetadata.class,
                                                declaration)))
                .expectNext("app_delivery:auto_submit_sweep")
                .expectComplete()
                .verify(TIMEOUT);
        assertThat(workerPool.getMetrics().orElseThrow().acquiredSize()).isZero();
        assertThat(physicalPool.getMetrics().orElseThrow().acquiredSize()).isZero();
    }

    @Test
    void contenderLosesImmediatelyAndCanAcquireAfterRelease() {
        SweepLock.Handle winner =
                Objects.requireNonNull(lock.tryAcquire("auto-submit").block(TIMEOUT));
        assertThat(physicalPool.getMetrics().orElseThrow().acquiredSize()).isEqualTo(1);

        StepVerifier.create(lock.tryAcquire("auto-submit")).expectComplete().verify(TIMEOUT);
        assertThat(physicalPool.getMetrics().orElseThrow().acquiredSize()).isEqualTo(1);

        StepVerifier.create(winner.assertHeld()).verifyComplete();
        StepVerifier.create(winner.close()).verifyComplete();
        SweepLock.Handle nextWinner =
                Objects.requireNonNull(lock.tryAcquire("auto-submit").block(TIMEOUT));
        StepVerifier.create(nextWinner.close()).verifyComplete();
    }

    @Test
    void transactionAndSessionAdaptersShareTheSameLockSpace() {
        SweepLock.Handle transactionWinner =
                Objects.requireNonNull(transactionLock.tryAcquire("retention").block(TIMEOUT));
        try {
            assertThat(physicalPool.getMetrics().orElseThrow().acquiredSize()).isEqualTo(1);
            StepVerifier.create(transactionLock.tryAcquire("retention"))
                    .expectComplete()
                    .verify(TIMEOUT);
            StepVerifier.create(lock.tryAcquire("retention")).expectComplete().verify(TIMEOUT);
            StepVerifier.create(transactionWinner.assertHeld()).expectComplete().verify(TIMEOUT);
        } finally {
            transactionWinner.close().block(TIMEOUT);
        }
        SweepLock.Handle sessionWinner =
                Objects.requireNonNull(lock.tryAcquire("retention").block(TIMEOUT));
        try {
            StepVerifier.create(transactionLock.tryAcquire("retention"))
                    .expectComplete()
                    .verify(TIMEOUT);
        } finally {
            sessionWinner.close().block(TIMEOUT);
        }
        SweepLock.Handle next =
                Objects.requireNonNull(transactionLock.tryAcquire("retention").block(TIMEOUT));
        StepVerifier.create(next.close()).expectComplete().verify(TIMEOUT);
        assertThat(physicalPool.getMetrics().orElseThrow().acquiredSize()).isZero();
    }

    @Test
    void registryReadsAllSixDisabledRowsThroughTheSecuredConnection() {
        StepVerifier.create(registryLoader.load())
                .assertNext(
                        rows -> {
                            assertThat(rows).hasSize(6);
                            assertThat(rows).noneMatch(SweepRegistryLoader.RegistryRow::enabled);
                        })
                .expectComplete()
                .verify(TIMEOUT);
        registryLoader.afterSingletonsInstantiated();
        assertThat(registryLoader.enabledSweeps()).isEmpty();
        assertThat(physicalPool.getMetrics().orElseThrow().acquiredSize()).isZero();
    }

    private static WorkloadDatabaseProperties.PoolProperties pool(
            String url, String password, int maxSize) {
        return new WorkloadDatabaseProperties.PoolProperties(
                url, "app_worker", password, 0, maxSize, Duration.ofMinutes(30));
    }

    private static ConnectionPool physicalPool(ConnectionFactory connectionFactory) {
        ConnectionFactory secured = ((TracingConnectionFactory) connectionFactory).delegate();
        return (ConnectionPool) ((SecurityContextInitializer) secured).delegate();
    }

    private void executeAsClusterOwner(String sql) throws Exception {
        try (var connection =
                        DriverManager.getConnection(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String readResource(String path) throws Exception {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
