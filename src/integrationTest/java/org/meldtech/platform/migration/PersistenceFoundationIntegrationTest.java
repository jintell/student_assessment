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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.meldtech.platform.PostgreSqlTestContainer;
import org.meldtech.platform.audit.testing.AuditPostgreSqlFixture;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersistenceFoundationIntegrationTest {

    private static final String TENANT_A = "00000000-0000-0000-0000-000000000031";
    private static final String TENANT_B = "00000000-0000-0000-0000-000000000032";
    private static final String MIGRATOR_PASSWORD = UUID.randomUUID().toString();

    private PostgreSQLContainer postgres;
    private AuditPostgreSqlFixture.Manifest auditFixtureManifest;

    @BeforeAll
    void migrateDatabase() throws Exception {
        postgres = PostgreSqlTestContainer.instance();
        postgres.start();
        executeAsClusterOwner(readResource("db/provisioning/V1__create_migration_role.sql"));
        executeAsClusterOwner("ALTER ROLE app_migrator PASSWORD '%s'".formatted(MIGRATOR_PASSWORD));

        runMigrations();
        try (Connection connection = clusterOwnerConnection()) {
            auditFixtureManifest = AuditPostgreSqlFixture.seed(connection);
        }
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
    void sweepRegistryHasApprovedOwnershipAndConstraints() throws SQLException {
        assertThat(
                        queryIntAsClusterOwner(
                                """
                SELECT count(*) FROM pg_catalog.pg_tables
                WHERE schemaname = 'platform' AND tablename = 'sweep_registry'
                  AND tableowner = 'app_migrator' AND NOT rowsecurity
                """))
                .isEqualTo(1);
        assertThat(
                        queryIntAsClusterOwner(
                                """
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'platform' AND table_name = 'sweep_registry'
                """))
                .isEqualTo(9);
        assertThat(
                        queryIntAsClusterOwner(
                                """
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'platform' AND table_name = 'sweep_registry'
                  AND column_name = 'tenant_id'
                """))
                .isZero();

        try (Connection connection = clusterOwnerConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute(
                        """
                        INSERT INTO platform.sweep_registry
                            (sweep_name, lock_key, owning_module, owning_feature,
                             default_interval_ms, system_actor)
                        VALUES ('registry-constraint-test', 101, 'platform', 'FEAT-PLAT-006',
                                1000, 'OUTBOX_RELAY')
                        """);
                try (ResultSet rows =
                        statement.executeQuery(
                                """
                        SELECT enabled, created_at = updated_at AS timestamps_match
                        FROM platform.sweep_registry WHERE sweep_name = 'registry-constraint-test'
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBoolean("enabled")).isFalse();
                    assertThat(rows.getBoolean("timestamps_match")).isTrue();
                }
                assertRegistryConstraintRejects(
                        connection,
                        """
                        INSERT INTO platform.sweep_registry
                            (sweep_name, lock_key, owning_module, owning_feature,
                             default_interval_ms, system_actor)
                        VALUES ('duplicate-key-test', 101, 'platform', 'FEAT-PLAT-006',
                                1000, 'OUTBOX_RELAY')
                        """,
                        "23505");
                assertRegistryConstraintRejects(
                        connection,
                        "UPDATE platform.sweep_registry SET default_interval_ms = 0 "
                                + "WHERE sweep_name = 'registry-constraint-test'",
                        "23514");
                assertRegistryConstraintRejects(
                        connection,
                        "UPDATE platform.sweep_registry SET sweep_name = 'Invalid_Name' "
                                + "WHERE sweep_name = 'registry-constraint-test'",
                        "23514");
                assertRegistryConstraintRejects(
                        connection,
                        "UPDATE platform.sweep_registry SET owning_feature = 'invalid' "
                                + "WHERE sweep_name = 'registry-constraint-test'",
                        "23514");
                assertRegistryConstraintRejects(
                        connection,
                        "UPDATE platform.sweep_registry SET updated_at = created_at - interval '1 second' "
                                + "WHERE sweep_name = 'registry-constraint-test'",
                        "23514");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void sweepRegistrySeedReservesExactDisabledIdentitiesAndIsRerunnable() throws Exception {
        String expected =
                """
                auto-submit|8663753269692232313|delivery|FEAT-DLV-004|10000|false|AUTO_SUBMIT_SWEEPER
                idp-reconciliation|-4891396382355818010|iam|FEAT-IAM-005|60000|false|IDP_RECONCILER
                outbox-backlog|722004001|outbox|FEAT-PLAT-004|60000|false|OUTBOX_RELAY
                outbox-relay|-2484966499978243116|outbox|FEAT-PLAT-004|200|false|OUTBOX_RELAY
                pin-material-destruction|-2916676166989647429|examaccess|FEAT-EXAM-002|60000|false|RETENTION_ENGINE
                retention|-4505843845379211855|audit|FEAT-PRIV-001|60000|false|RETENTION_ENGINE
                stale-claim-reclaim|-6495990722266513791|grading|FEAT-GRD-002|30000|false|GRADING_WORKER
                """;

        assertThat(sweepRegistryRows()).isEqualTo(expected);
        executeAsClusterOwner(readResource("db/migration/platform/V10__seed_sweep_registry.sql"));
        assertThat(sweepRegistryRows()).isEqualTo(expected);
    }

    private String sweepRegistryRows() throws SQLException {
        try (Connection connection = clusterOwnerConnection();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                """
                                SELECT sweep_name, lock_key, owning_module, owning_feature,
                                       default_interval_ms, enabled, system_actor
                                FROM platform.sweep_registry
                                ORDER BY sweep_name
                                """)) {
            var result = new StringBuilder();
            while (rows.next()) {
                result.append(
                        "%s|%d|%s|%s|%d|%s|%s%n"
                                .formatted(
                                        rows.getString("sweep_name"),
                                        rows.getLong("lock_key"),
                                        rows.getString("owning_module"),
                                        rows.getString("owning_feature"),
                                        rows.getLong("default_interval_ms"),
                                        rows.getBoolean("enabled"),
                                        rows.getString("system_actor")));
            }
            return result.toString();
        }
    }

    private static void assertRegistryConstraintRejects(
            Connection connection, String sql, String sqlState) throws SQLException {
        var savepoint = connection.setSavepoint();
        try (Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute(sql))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState())
                    .isEqualTo(sqlState);
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
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

    @Test
    void auditShardHeadsArePreProvisionedIdempotentlyWithDeterministicSeeds() throws SQLException {
        String tenantId = "00000000-0000-0000-0000-000000000061";
        executeAsClusterOwner(
                """
                SET ROLE app_migrator;
                SELECT audit.provision_audit_epoch_heads(
                    '%s',
                    'GENERAL_AUDIT_EVENT',
                    date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date,
                    4,
                    1::smallint
                );
                SELECT audit.provision_audit_epoch_heads(
                    '%s',
                    'GENERAL_AUDIT_EVENT',
                    date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date,
                    4,
                    1::smallint
                );
                RESET ROLE;
                """
                        .formatted(tenantId, tenantId));

        assertThat(
                        queryIntAsClusterOwner(
                                """
                                SELECT count(*)
                                FROM audit.audit_chain_head
                                WHERE tenant_id = '%s'
                                  AND retention_class = 'GENERAL_AUDIT_EVENT'
                                  AND seq = 0
                                  AND shard_count = 4
                                  AND octet_length(head_hash) = 32
                                """
                                        .formatted(tenantId)))
                .isEqualTo(4);
        assertThat(
                        queryIntAsClusterOwner(
                                """
                                SELECT count(DISTINCT encode(head_hash, 'hex'))
                                FROM audit.audit_chain_head
                                WHERE tenant_id = '%s'
                                  AND retention_class = 'GENERAL_AUDIT_EVENT'
                                """
                                        .formatted(tenantId)))
                .isEqualTo(4);
    }

    @Test
    void auditFixtureSeedsMixedRetentionAcrossTwoUtcMonthBoundaries() throws SQLException {
        assertThat(auditFixtureManifest.events()).hasSize(24);
        assertThat(auditFixtureManifest.utcMonthBoundaries()).hasSize(2);
        assertThat(auditFixtureManifest.legalHolds()).hasSize(1);
        assertThat(auditFixtureManifest.eligibleDispositionUnits()).hasSize(4);
        assertThat(
                        queryIntAsClusterOwner(
                                """
                                SELECT count(DISTINCT retention_class)
                                FROM audit.audit_event
                                WHERE tenant_id = '%s'
                                """
                                        .formatted(AuditPostgreSqlFixture.TENANT_ID)))
                .isEqualTo(4);
        assertThat(
                        queryIntAsClusterOwner(
                                """
                                SELECT count(DISTINCT period)
                                FROM audit.audit_event
                                WHERE tenant_id = '%s'
                                """
                                        .formatted(AuditPostgreSqlFixture.TENANT_ID)))
                .isEqualTo(3);
        assertThat(
                        queryIntAsClusterOwner(
                                """
                                SELECT count(DISTINCT shard_id)
                                FROM audit.audit_event
                                WHERE tenant_id = '%s'
                                """
                                        .formatted(AuditPostgreSqlFixture.TENANT_ID)))
                .isEqualTo(2);

        AuditPostgreSqlFixture.LegalHoldSeed legalHold =
                auditFixtureManifest.legalHolds().getFirst();
        assertThat(
                        queryIntAsClusterOwner(
                                """
                                SELECT count(*)
                                FROM audit.audit_event
                                WHERE tenant_id = '%s'
                                  AND retention_class = '%s'
                                  AND period = DATE '%s'
                                """
                                        .formatted(
                                                AuditPostgreSqlFixture.TENANT_ID,
                                                legalHold.promotedEpoch().retentionClass(),
                                                legalHold.promotedEpoch().period())))
                .isEqualTo(2);
        assertThat(
                        auditFixtureManifest.events().stream()
                                .filter(
                                        seeded ->
                                                seeded.event()
                                                        .eventId()
                                                        .equals(legalHold.coveredEventId())))
                .hasSize(1);
    }

    @Test
    @Tag("audit-daily-chain-verification")
    void dailyAuditChainVerificationWalksEveryOpenFixtureShard() throws SQLException {
        assertThat(
                        queryIntAsClusterOwner(
                                """
                                SELECT count(*)
                                FROM audit.audit_event AS event
                                JOIN audit.audit_chain_head AS head
                                  ON head.tenant_id = event.tenant_id
                                 AND head.retention_class = event.retention_class
                                 AND head.period = event.period
                                 AND head.shard_id = event.shard_id
                                WHERE event.tenant_id = '%s'
                                  AND event.seq = 1
                                  AND event.prev_hash = audit.audit_chain_seed(
                                      event.tenant_id,
                                      event.retention_class,
                                      event.period,
                                      event.shard_id,
                                      head.shard_count
                                  )
                                  AND event.record_hash = audit.digest(
                                      event.prev_hash
                                      || convert_to('meldtech.audit.fixture.record.v1', 'UTF8')
                                      || decode('00', 'hex')
                                      || convert_to(event.audit_event_id::text, 'UTF8'),
                                      'sha256'
                                  )
                                  AND head.seq = event.seq
                                  AND head.head_hash = event.record_hash
                                """
                                        .formatted(AuditPostgreSqlFixture.TENANT_ID)))
                .isEqualTo(auditFixtureManifest.events().size());
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
