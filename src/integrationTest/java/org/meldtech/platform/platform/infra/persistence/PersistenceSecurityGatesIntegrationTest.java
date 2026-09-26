package org.meldtech.platform.platform.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
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
    void forcedRlsGateRejectsAnUnprotectedTenantTableAndRollsItBack() throws SQLException {
        GrantMatrix matrix = new GrantMatrixLoader().loadDefault();
        var applicationSchemas =
                matrix.schemas().stream().map(GrantMatrix.SchemaGrant::name).toList();
        try (Connection connection = clusterOwnerConnection();
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute(
                        """
                        CREATE TABLE platform.p7_unforced_rls (
                            tenant_id uuid NOT NULL,
                            probe_id uuid PRIMARY KEY
                        )
                        """);

                assertThatThrownBy(
                                () ->
                                        PostgreSqlCatalogGate.verifyForcedRls(
                                                connection, applicationSchemas))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("RLS_CATALOG_GATE: platform.p7_unforced_rls")
                        .hasMessageContaining("must enable and force RLS");
            } finally {
                connection.rollback();
            }

            PostgreSqlCatalogGate.verifyForcedRls(connection, applicationSchemas);
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
    void crossSchemaForeignKeyGateRejectsAViolationAndRollsItBack() throws SQLException {
        GrantMatrix matrix = new GrantMatrixLoader().loadDefault();
        var applicationSchemas =
                matrix.schemas().stream().map(GrantMatrix.SchemaGrant::name).toList();
        try (Connection connection = clusterOwnerConnection();
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("CREATE TABLE tenancy.p7_fk_parent (id uuid PRIMARY KEY)");
                statement.execute(
                        """
                        CREATE TABLE platform.p7_fk_child (
                            id uuid PRIMARY KEY,
                            parent_id uuid REFERENCES tenancy.p7_fk_parent(id)
                        )
                        """);

                assertThatThrownBy(
                                () ->
                                        PostgreSqlCatalogGate.verifyNoCrossSchemaForeignKeys(
                                                connection, applicationSchemas))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("CROSS_SCHEMA_FOREIGN_KEY_GATE")
                        .hasMessageContaining("platform.p7_fk_child -> tenancy.p7_fk_parent");
            } finally {
                connection.rollback();
            }

            PostgreSqlCatalogGate.verifyNoCrossSchemaForeignKeys(connection, applicationSchemas);
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
    void liveGrantSetPreservesCompositeRoleNarrowness() throws SQLException {
        GrantMatrix matrix = new GrantMatrixLoader().loadDefault();
        try (Connection connection = clusterOwnerConnection()) {
            GrantDiffGate.verify(connection, matrix);
            CompositeRoleNarrownessVerifier.verify(matrix);
        }
    }

    @Test
    void moduleRoleGrantsMatchOwnedSchemaAndSharedInsertContracts() throws SQLException {
        GrantMatrix matrix = new GrantMatrixLoader().loadDefault();
        Set<String> moduleSchemas =
                matrix.schemas().stream()
                        .map(GrantMatrix.SchemaGrant::name)
                        .filter(schema -> !Set.of("audit", "outbox", "platform").contains(schema))
                        .collect(Collectors.toUnmodifiableSet());
        Set<String> moduleRoles =
                moduleSchemas.stream()
                        .map(schema -> "app_" + schema)
                        .collect(Collectors.toUnmodifiableSet());

        assertThat(moduleRoles).hasSize(12);
        for (String schema : moduleSchemas) {
            String role = "app_" + schema;
            assertThat(
                            matrix.objectGrants().stream()
                                    .filter(grant -> grant.grantee().equals(role))
                                    .map(
                                            grant ->
                                                    grant.objectType()
                                                            + ":"
                                                            + grant.schema()
                                                            + ":"
                                                            + grant.privileges())
                                    .toList())
                    .as("declared object grants for %s", role)
                    .containsExactlyInAnyOrder(
                            "SCHEMA:" + schema + ":[USAGE]",
                            "ALL_TABLES_IN_SCHEMA:" + schema + ":[DELETE, INSERT, SELECT, UPDATE]",
                            "SCHEMA:audit:[USAGE]",
                            "SCHEMA:outbox:[USAGE]");
        }

        for (String sharedSchema : List.of("audit", "outbox")) {
            assertThat(matrix.defaultPrivileges())
                    .filteredOn(grant -> grant.schema().equals(sharedSchema))
                    .singleElement()
                    .satisfies(
                            grant -> {
                                assertThat(grant.owner()).isEqualTo("app_migrator");
                                assertThat(grant.objectType())
                                        .isEqualTo(GrantMatrix.ObjectType.TABLE);
                                assertThat(grant.grantees()).containsAll(moduleRoles);
                                assertThat(grant.privileges())
                                        .containsExactly(GrantMatrix.Privilege.INSERT);
                            });
        }

        String quotedModuleRoles =
                moduleRoles.stream()
                        .sorted()
                        .map(role -> "'" + role + "'")
                        .collect(Collectors.joining(","));
        assertThat(
                        queryIntAsClusterOwner(
                                """
                                SELECT count(*)
                                FROM pg_catalog.pg_default_acl AS defaults
                                JOIN pg_catalog.pg_namespace AS namespace
                                  ON namespace.oid = defaults.defaclnamespace
                                CROSS JOIN LATERAL pg_catalog.aclexplode(defaults.defaclacl) AS acl
                                JOIN pg_catalog.pg_roles AS grantee ON grantee.oid = acl.grantee
                                WHERE namespace.nspname = 'audit'
                                  AND grantee.rolname IN (%s)
                                  AND acl.privilege_type IN ('UPDATE', 'DELETE')
                                """
                                        .formatted(quotedModuleRoles)))
                .as("module-role audit mutation default privileges")
                .isZero();

        try (Connection connection = clusterOwnerConnection()) {
            GrantDiffGate.verify(connection, matrix);
        }
    }

    @Test
    void auditDefaultPrivilegesApplyToTablesCreatedByLaterMigrations() throws SQLException {
        migrateDefaultPrivilegeProbe();
        try {
            executeAsRole(
                    "app_delivery",
                    """
                    INSERT INTO audit.p7_default_privilege_probe (probe_id, payload)
                    VALUES ('70000000-0000-0000-0000-000000000015', 'insert allowed')
                    """);

            assertPrivilegeDenied(
                    "UPDATE audit.p7_default_privilege_probe SET payload = 'forbidden'");
            assertPrivilegeDenied("DELETE FROM audit.p7_default_privilege_probe");

            assertThat(
                            queryIntAsClusterOwner(
                                    """
                                    SELECT count(*)
                                    FROM information_schema.role_table_grants
                                    WHERE grantee = 'app_delivery'
                                      AND table_schema = 'audit'
                                      AND table_name = 'p7_default_privilege_probe'
                                      AND privilege_type = 'INSERT'
                                    """))
                    .isEqualTo(1);
            assertThat(
                            queryIntAsClusterOwner(
                                    """
                                    SELECT count(*)
                                    FROM information_schema.role_table_grants
                                    WHERE grantee = 'app_delivery'
                                      AND table_schema = 'audit'
                                      AND table_name = 'p7_default_privilege_probe'
                                      AND privilege_type IN ('UPDATE', 'DELETE')
                                    """))
                    .isZero();
        } finally {
            executeAsClusterOwner(
                    """
                    DROP TABLE IF EXISTS audit.p7_default_privilege_probe;
                    DROP TABLE IF EXISTS platform_migrations.flyway_schema_history_p7_15;
                    """);
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

    private void executeAsRole(String role, String sql) throws SQLException {
        try (Connection connection = clusterOwnerConnection();
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("SET LOCAL ROLE " + role);
                statement.execute(sql);
            } finally {
                connection.rollback();
            }
        }
    }

    private void assertPrivilegeDenied(String sql) {
        assertThatThrownBy(() -> executeAsRole("app_delivery", sql))
                .isInstanceOf(SQLException.class)
                .satisfies(
                        failure ->
                                assertThat(((SQLException) failure).getSQLState())
                                        .isEqualTo("42501"));
    }

    private void migrateDefaultPrivilegeProbe() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), "app_migrator", MIGRATOR_PASSWORD)
                .defaultSchema("platform_migrations")
                .schemas("platform_migrations")
                .createSchemas(true)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .table("flyway_schema_history_p7_15")
                .locations("classpath:db/test-migration/p7_15")
                .load()
                .migrate();
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
