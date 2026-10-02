package org.meldtech.platform.platform.infra.outbox;

import static io.r2dbc.spi.ConnectionFactoryOptions.DATABASE;
import static io.r2dbc.spi.ConnectionFactoryOptions.DRIVER;
import static io.r2dbc.spi.ConnectionFactoryOptions.HOST;
import static io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD;
import static io.r2dbc.spi.ConnectionFactoryOptions.PORT;
import static io.r2dbc.spi.ConnectionFactoryOptions.USER;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.meldtech.platform.PostgreSqlTestContainer;
import org.meldtech.platform.migration.MigrationApplication;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class OutboxPostgreSqlIntegrationTestSupport {

    private static final Object MIGRATION_MONITOR = new Object();
    private static final String MIGRATOR_PASSWORD = UUID.randomUUID().toString();
    private static boolean migrated;

    protected PostgreSQLContainer postgres;
    protected DatabaseClient databaseClient;
    protected TransactionalOperator transactions;

    @BeforeAll
    final void prepareOutboxDatabase() throws Exception {
        postgres = PostgreSqlTestContainer.instance();
        postgres.start();
        migrateOnce();
        createBusinessProbe();

        ConnectionFactory connectionFactory =
                ConnectionFactories.get(
                        ConnectionFactoryOptions.builder()
                                .option(DRIVER, "postgresql")
                                .option(HOST, postgres.getHost())
                                .option(PORT, postgres.getMappedPort(5432))
                                .option(DATABASE, postgres.getDatabaseName())
                                .option(USER, postgres.getUsername())
                                .option(PASSWORD, postgres.getPassword())
                                .build());
        databaseClient = DatabaseClient.create(connectionFactory);
        transactions = TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
    }

    @AfterEach
    final void cleanOutboxFixtures() throws SQLException {
        executeAsClusterOwner(
                """
                TRUNCATE TABLE delivery.outbox_consumer_effect,
                    delivery.processed_event,
                    delivery.outbox_atomicity_probe,
                    outbox.outbox_event
                """);
    }

    @AfterAll
    final void removeBusinessProbe() throws SQLException {
        executeAsClusterOwner(
                """
                DROP TABLE IF EXISTS delivery.outbox_consumer_effect;
                DROP TABLE IF EXISTS delivery.processed_event;
                DROP TABLE IF EXISTS delivery.outbox_atomicity_probe;
                """);
    }

    protected Mono<Void> installModuleContext(UUID tenantId) {
        return databaseClient
                .sql("SET LOCAL ROLE app_delivery")
                .fetch()
                .rowsUpdated()
                .then(
                        databaseClient
                                .sql("SELECT set_config('app.tenant_id', :tenantId, true)")
                                .bind("tenantId", tenantId.toString())
                                .fetch()
                                .rowsUpdated())
                .then();
    }

    protected int queryInt(String sql) throws SQLException {
        try (Connection connection = clusterOwnerConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    protected String queryString(String sql) throws SQLException {
        try (Connection connection = clusterOwnerConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    protected void executeAsClusterOwner(String sql) throws SQLException {
        try (Connection connection = clusterOwnerConnection();
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    protected Connection clusterOwnerConnection() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private void migrateOnce() throws Exception {
        synchronized (MIGRATION_MONITOR) {
            if (migrated) {
                return;
            }
            executeAsClusterOwner(readResource("db/provisioning/V1__create_migration_role.sql"));
            executeAsClusterOwner("ALTER ROLE app_migrator PASSWORD '" + MIGRATOR_PASSWORD + "'");
            MigrationApplication.run(
                    new String[] {
                        "--migrate-only",
                        "--cbt.migration.jdbc-url=" + postgres.getJdbcUrl(),
                        "--cbt.migration.username=app_migrator",
                        "--cbt.migration.classification=EXPAND",
                        "--cbt.database.roles.app-migrator.password=" + MIGRATOR_PASSWORD
                    });
            migrated = true;
        }
    }

    private void createBusinessProbe() throws SQLException {
        executeAsClusterOwner(
                """
                CREATE TABLE IF NOT EXISTS delivery.outbox_atomicity_probe (
                    tenant_id uuid NOT NULL,
                    probe_id uuid NOT NULL,
                    PRIMARY KEY (tenant_id, probe_id)
                );
                ALTER TABLE delivery.outbox_atomicity_probe ENABLE ROW LEVEL SECURITY;
                ALTER TABLE delivery.outbox_atomicity_probe FORCE ROW LEVEL SECURITY;
                DROP POLICY IF EXISTS tenant_isolation ON delivery.outbox_atomicity_probe;
                CREATE POLICY tenant_isolation ON delivery.outbox_atomicity_probe
                    FOR ALL TO app_delivery
                    USING (tenant_id = current_setting('app.tenant_id', false)::uuid)
                    WITH CHECK (tenant_id = current_setting('app.tenant_id', false)::uuid);
                GRANT SELECT, INSERT, DELETE ON delivery.outbox_atomicity_probe TO app_delivery;

                CREATE TABLE IF NOT EXISTS delivery.processed_event (
                    outbox_event_id uuid PRIMARY KEY,
                    tenant_id uuid NOT NULL,
                    event_type text NOT NULL,
                    processed_at timestamp with time zone NOT NULL,
                    outcome varchar(24) NOT NULL
                        CHECK (outcome IN ('APPLIED', 'BUSINESS_DUPLICATE', 'STALE_VERSION'))
                );
                ALTER TABLE delivery.processed_event ENABLE ROW LEVEL SECURITY;
                ALTER TABLE delivery.processed_event FORCE ROW LEVEL SECURITY;
                DROP POLICY IF EXISTS tenant_processed_event ON delivery.processed_event;
                CREATE POLICY tenant_processed_event ON delivery.processed_event
                    FOR ALL TO app_delivery
                    USING (tenant_id = current_setting('app.tenant_id', false)::uuid)
                    WITH CHECK (tenant_id = current_setting('app.tenant_id', false)::uuid);
                GRANT SELECT, INSERT, UPDATE, DELETE ON delivery.processed_event TO app_delivery;

                CREATE TABLE IF NOT EXISTS delivery.outbox_consumer_effect (
                    tenant_id uuid NOT NULL,
                    business_key text NOT NULL,
                    applied_revision integer NOT NULL,
                    PRIMARY KEY (tenant_id, business_key)
                );
                ALTER TABLE delivery.outbox_consumer_effect ENABLE ROW LEVEL SECURITY;
                ALTER TABLE delivery.outbox_consumer_effect FORCE ROW LEVEL SECURITY;
                DROP POLICY IF EXISTS tenant_isolation ON delivery.outbox_consumer_effect;
                CREATE POLICY tenant_isolation ON delivery.outbox_consumer_effect
                    FOR ALL TO app_delivery
                    USING (tenant_id = current_setting('app.tenant_id', false)::uuid)
                    WITH CHECK (tenant_id = current_setting('app.tenant_id', false)::uuid);
                GRANT SELECT, INSERT, UPDATE, DELETE
                    ON delivery.outbox_consumer_effect TO app_delivery;
                """);
    }

    private static String readResource(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
