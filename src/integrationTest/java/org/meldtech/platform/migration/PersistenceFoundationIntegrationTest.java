package org.meldtech.platform.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.meldtech.platform.PostgreSqlTestContainer;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersistenceFoundationIntegrationTest {

    private static final String TENANT_A = "00000000-0000-0000-0000-000000000031";
    private static final String TENANT_B = "00000000-0000-0000-0000-000000000032";
    private static final String MIGRATOR_PASSWORD = UUID.randomUUID().toString();

    private PostgreSQLContainer postgres;

    @BeforeAll
    void migrateDatabase() throws Exception {
        postgres = PostgreSqlTestContainer.instance();
        postgres.start();
        executeAsClusterOwner(readResource("db/provisioning/V1__create_migration_role.sql"));
        executeAsClusterOwner("ALTER ROLE app_migrator PASSWORD '%s'".formatted(MIGRATOR_PASSWORD));

        runMigrations();
    }

    @Test
    void migrationsCreateOwnedSchemasAndIndependentHistories() throws SQLException {
        assertThat(
                        queryIntAsClusterOwner(
                                """
                                SELECT count(*)
                                FROM pg_catalog.pg_namespace AS namespace
                                JOIN pg_catalog.pg_roles AS owner ON owner.oid = namespace.nspowner
                                WHERE namespace.nspname IN (
                                    'tenancy', 'iam', 'academic', 'people', 'questionbank',
                                    'authoring', 'examaccess', 'delivery', 'grading', 'result',
                                    'correction', 'notification', 'audit', 'outbox', 'platform'
                                )
                                AND owner.rolname = 'app_migrator'
                                """))
                .isEqualTo(15);
        assertThat(
                        queryIntAsClusterOwner(
                                """
                                SELECT count(*)
                                FROM pg_catalog.pg_tables
                                WHERE schemaname = 'platform_migrations'
                                AND tablename LIKE 'flyway_schema_history_%'
                                """))
                .isEqualTo(15);
    }

    @Test
    void forcedRlsRestrictsUnfilteredQueriesAndRejectsMissingContext() throws SQLException {
        executeAsClusterOwner(
                """
                INSERT INTO platform.tenant_scope_probe (tenant_id, probe_id)
                VALUES
                    ('%s', '10000000-0000-0000-0000-000000000031'),
                    ('%s', '10000000-0000-0000-0000-000000000032')
                """
                        .formatted(TENANT_A, TENANT_B));

        assertThat(queryProbeAsMigrator(TENANT_A)).isEqualTo(1);
        assertThatThrownBy(this::queryProbeWithoutContext).isInstanceOf(SQLException.class);
    }

    @Test
    void missingTenantPredicateLeaksForeignRowsWhenRlsIsDisabled() throws SQLException {
        try (Connection connection = clusterOwnerConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute(
                        """
                        INSERT INTO platform.tenant_scope_probe (tenant_id, probe_id)
                        VALUES
                            ('00000000-0000-0000-0000-000000000041',
                             '10000000-0000-0000-0000-000000000041'),
                            ('00000000-0000-0000-0000-000000000042',
                             '10000000-0000-0000-0000-000000000042')
                        """);
                statement.execute(
                        "ALTER TABLE platform.tenant_scope_probe DISABLE ROW LEVEL SECURITY");
                statement.execute("SET LOCAL ROLE app_migrator");

                try (ResultSet rows =
                        statement.executeQuery(
                                """
                                SELECT count(*)
                                FROM platform.tenant_scope_probe
                                WHERE probe_id IN (
                                    '10000000-0000-0000-0000-000000000041',
                                    '10000000-0000-0000-0000-000000000042'
                                )
                                """)) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(2);
                }
            } finally {
                connection.rollback();
            }
        }

        assertThat(
                        queryIntAsClusterOwner(
                                """
                                SELECT count(*)
                                FROM pg_catalog.pg_class AS relation
                                JOIN pg_catalog.pg_namespace AS namespace
                                  ON namespace.oid = relation.relnamespace
                                WHERE namespace.nspname = 'platform'
                                  AND relation.relname = 'tenant_scope_probe'
                                  AND relation.relrowsecurity
                                  AND relation.relforcerowsecurity
                                """))
                .isEqualTo(1);
    }

    @Test
    void forcedRlsHidesForeignRowWhenTenantPredicateIsOmitted() throws SQLException {
        executeAsClusterOwner(
                """
                INSERT INTO platform.tenant_scope_probe (tenant_id, probe_id)
                VALUES
                    ('00000000-0000-0000-0000-000000000051',
                     '10000000-0000-0000-0000-000000000051'),
                    ('00000000-0000-0000-0000-000000000052',
                     '10000000-0000-0000-0000-000000000052')
                ON CONFLICT DO NOTHING
                """);

        try (Connection connection = clusterOwnerConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET LOCAL ROLE app_migrator");
            statement.execute("SET LOCAL app.tenant_id = '00000000-0000-0000-0000-000000000051'");
            try (ResultSet rows =
                    statement.executeQuery(
                            """
                            SELECT count(*)
                            FROM platform.tenant_scope_probe
                            WHERE probe_id = '10000000-0000-0000-0000-000000000052'
                            """)) {
                rows.next();
                assertThat(rows.getInt(1)).isZero();
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void repeatableCompositeGrantRefreshesAfterAnOwnedTableIsAdded() throws SQLException {
        executeAsClusterOwner(
                """
                SET ROLE app_migrator;
                CREATE TABLE examaccess.exam_access_pin (probe_id uuid);
                RESET ROLE;
                """);

        runMigrations();

        assertThat(
                        queryIntAsClusterOwner(
                                """
                                SELECT count(*)
                                FROM information_schema.role_table_grants
                                WHERE grantee = 'app_txn_examentry'
                                  AND table_schema = 'examaccess'
                                  AND table_name = 'exam_access_pin'
                                  AND privilege_type IN ('SELECT', 'INSERT', 'UPDATE')
                                """))
                .isEqualTo(3);
    }

    @Test
    void databaseCompositeRolesExactlyMatchTheClosedFlowEnumeration() throws SQLException {
        try (Connection connection = clusterOwnerConnection()) {
            CompositeRoleGrantAudit.verify(connection);
        }
    }

    private void runMigrations() {
        MigrationApplication.run(
                new String[] {
                    "--migrate-only",
                    "--cbt.migration.jdbc-url=" + postgres.getJdbcUrl(),
                    "--cbt.migration.username=app_migrator",
                    "--cbt.migration.classification=EXPAND",
                    "--cbt.database.roles.app-migrator.password=" + MIGRATOR_PASSWORD
                });
    }

    private int queryProbeAsMigrator(String tenantId) throws SQLException {
        try (Connection connection = clusterOwnerConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET LOCAL ROLE app_migrator");
            statement.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            try (ResultSet resultSet =
                    statement.executeQuery("SELECT count(*) FROM platform.tenant_scope_probe")) {
                resultSet.next();
                int count = resultSet.getInt(1);
                connection.rollback();
                return count;
            }
        }
    }

    private void queryProbeWithoutContext() throws SQLException {
        try (Connection connection = clusterOwnerConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE app_migrator");
            statement.executeQuery("SELECT count(*) FROM platform.tenant_scope_probe");
        }
    }

    private int queryIntAsClusterOwner(String sql) throws SQLException {
        try (Connection connection = clusterOwnerConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private void executeAsClusterOwner(String sql) throws SQLException {
        try (Connection connection = clusterOwnerConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Connection clusterOwnerConnection() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private String readResource(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
