package org.meldtech.platform.platform.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.meldtech.platform.PostgreSqlTestContainer;
import org.meldtech.platform.migration.MigrationApplication;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersistenceSecurityGatesIntegrationTest {

    private static final String MIGRATOR_PASSWORD = UUID.randomUUID().toString();

    private PostgreSQLContainer postgres;

    @BeforeAll
    void migrateDatabase() throws Exception {
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
    }

    @Test
    void everyTenantTableHasTheStrictForcedRlsPolicy() throws SQLException {
        GrantMatrix matrix = new GrantMatrixLoader().loadDefault();
        try (Connection connection = clusterOwnerConnection()) {
            PostgreSqlCatalogGate.verifyForcedRls(
                    connection,
                    matrix.schemas().stream().map(GrantMatrix.SchemaGrant::name).toList());
        }
    }

    @Test
    void noForeignKeySpansApplicationSchemas() throws SQLException {
        GrantMatrix matrix = new GrantMatrixLoader().loadDefault();
        try (Connection connection = clusterOwnerConnection()) {
            PostgreSqlCatalogGate.verifyNoCrossSchemaForeignKeys(
                    connection,
                    matrix.schemas().stream().map(GrantMatrix.SchemaGrant::name).toList());
        }
    }

    @Test
    void liveDatabaseGrantsExactlyMatchTheDeclaredMatrix() throws SQLException {
        GrantMatrix matrix = new GrantMatrixLoader().loadDefault();
        try (Connection connection = clusterOwnerConnection()) {
            GrantDiffGate.verify(connection, matrix);
        }
    }

    @Test
    void examEntryRoleCannotUpdateOrDeleteAnswerTables() throws SQLException {
        assertThat(
                        queryIntAsClusterOwner(
                                """
                                SELECT count(*)
                                FROM information_schema.role_table_grants
                                WHERE grantee = 'app_txn_examentry'
                                  AND table_schema = 'delivery'
                                  AND table_name IN ('answer', 'answer_operation')
                                  AND privilege_type IN ('UPDATE', 'DELETE')
                                """))
                .as("exam-entry grants that mutate answer records")
                .isZero();
    }

    @Test
    void examEntryRoleCannotWriteReferenceSchemas() throws SQLException {
        assertThat(
                        queryIntAsClusterOwner(
                                """
                                SELECT count(*)
                                FROM information_schema.role_table_grants
                                WHERE grantee = 'app_txn_examentry'
                                  AND table_schema IN ('people', 'authoring', 'tenancy')
                                  AND privilege_type IN ('INSERT', 'UPDATE', 'DELETE')
                                """))
                .as("exam-entry write grants on reference schemas")
                .isZero();
    }

    @Test
    void examEntryRoleCannotReachExcludedSchemas() throws SQLException {
        assertThat(
                        queryIntAsClusterOwner(
                                """
                                SELECT count(*)
                                FROM (
                                    VALUES
                                        ('grading'),
                                        ('result'),
                                        ('correction'),
                                        ('notification'),
                                        ('questionbank'),
                                        ('iam'),
                                        ('platform')
                                ) AS excluded(schema_name)
                                WHERE has_schema_privilege(
                                    'app_txn_examentry', excluded.schema_name, 'USAGE')
                                   OR EXISTS (
                                       SELECT 1
                                       FROM information_schema.role_table_grants AS grant_fact
                                       WHERE grant_fact.grantee = 'app_txn_examentry'
                                         AND grant_fact.table_schema = excluded.schema_name
                                   )
                                """))
                .as("excluded schemas reachable by the exam-entry role")
                .isZero();
    }

    @Test
    void examEntryRoleCannotUpdateOrDeleteAuditRecords() throws SQLException {
        assertThat(
                        queryIntAsClusterOwner(
                                """
                                SELECT count(*)
                                FROM information_schema.role_table_grants
                                WHERE grantee = 'app_txn_examentry'
                                  AND table_schema = 'audit'
                                  AND privilege_type IN ('UPDATE', 'DELETE')
                                """))
                .as("exam-entry grants that mutate audit records")
                .isZero();
    }

    @Test
    void readonlyOperationsRoleHasNoDirectTableGrant() throws SQLException {
        assertThat(
                        queryIntAsClusterOwner(
                                """
                                SELECT count(*)
                                FROM information_schema.role_table_grants AS grant_fact
                                JOIN pg_catalog.pg_namespace AS namespace
                                  ON namespace.nspname = grant_fact.table_schema
                                JOIN pg_catalog.pg_class AS relation
                                  ON relation.relnamespace = namespace.oid
                                 AND relation.relname = grant_fact.table_name
                                WHERE grant_fact.grantee = 'app_readonly_ops'
                                  AND relation.relkind IN ('r', 'p')
                                """))
                .as("direct base-table grants held by app_readonly_ops")
                .isZero();
    }

    @Test
    void readonlyOperationsViewExposesOnlyApprovedCounters() throws SQLException {
        assertThat(
                        queryIntAsClusterOwner(
                                """
                                SELECT count(*)
                                FROM information_schema.role_table_grants
                                WHERE grantee = 'app_readonly_ops'
                                  AND NOT (
                                      table_schema = 'platform'
                                      AND table_name = 'database_diagnostics'
                                      AND privilege_type = 'SELECT'
                                  )
                                """))
                .as("grants outside the approved operations view")
                .isZero();
        assertThat(
                        queryIntAsClusterOwner(
                                """
                                SELECT count(*)
                                FROM information_schema.columns
                                WHERE table_schema = 'platform'
                                  AND table_name = 'database_diagnostics'
                                  AND column_name IN (
                                      'numbackends',
                                      'xact_commit',
                                      'xact_rollback',
                                      'blks_read',
                                      'blks_hit',
                                      'deadlocks'
                                  )
                                """))
                .as("approved non-PII columns in the operations view")
                .isEqualTo(6);
        assertThat(
                        queryIntAsClusterOwner(
                                """
                                SELECT count(*)
                                FROM information_schema.columns
                                WHERE table_schema = 'platform'
                                  AND table_name = 'database_diagnostics'
                                """))
                .as("total columns in the operations view")
                .isEqualTo(6);
    }

    private void executeAsClusterOwner(String sql) throws SQLException {
        try (Connection connection = clusterOwnerConnection();
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Connection clusterOwnerConnection() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private int queryIntAsClusterOwner(String sql) throws SQLException {
        try (Connection connection = clusterOwnerConnection();
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private String readResource(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
