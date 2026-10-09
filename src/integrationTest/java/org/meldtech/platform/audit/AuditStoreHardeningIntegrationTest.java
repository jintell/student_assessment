package org.meldtech.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.R2dbcException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.meldtech.platform.PostgreSqlTestContainer;
import org.meldtech.platform.audit.slice.getComplianceAuditEvents.ComplianceCursor;
import org.meldtech.platform.audit.slice.getComplianceAuditEvents.Request;
import org.meldtech.platform.audit.slice.getComplianceAuditEvents.infra.R2dbcComplianceAuditQueries;
import org.meldtech.platform.audit.testing.AuditPostgreSqlFixture;
import org.meldtech.platform.migration.MigrationApplication;
import org.meldtech.platform.platform.api.TransactionalConnection;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.annotation.Id;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditStoreHardeningIntegrationTest {

    private PostgreSQLContainer postgres;
    private String jdbcUrl;
    private String database;
    private List<String> applicationRoles;

    @BeforeAll
    void migrateAndSeed() throws Exception {
        postgres = PostgreSqlTestContainer.instance();
        postgres.start();
        database = "audit_hardening_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection =
                        DriverManager.getConnection(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        jdbcUrl =
                "jdbc:postgresql://"
                        + postgres.getHost()
                        + ":"
                        + postgres.getMappedPort(5432)
                        + "/"
                        + database;
        String password = UUID.randomUUID().toString();
        try (Connection connection = ownerConnection();
                var statement = connection.createStatement();
                var input =
                        new ClassPathResource("db/provisioning/V1__create_migration_role.sql")
                                .getInputStream()) {
            statement.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            statement.execute("ALTER ROLE app_migrator PASSWORD '" + password + "'");
        }
        MigrationApplication.run(
                new String[] {
                    "--migrate-only",
                    "--cbt.migration.jdbc-url=" + jdbcUrl,
                    "--cbt.migration.username=app_migrator",
                    "--cbt.migration.classification=EXPAND",
                    "--cbt.database.roles.app-migrator.password=" + password
                });
        try (Connection connection = ownerConnection()) {
            AuditPostgreSqlFixture.seed(connection);
            var roles = new ArrayList<String>();
            try (var statement = connection.createStatement();
                    var rows =
                            statement.executeQuery(
                                    """
                            SELECT rolname FROM pg_catalog.pg_roles
                            WHERE starts_with(rolname, 'app_') AND rolname <> 'app_migrator'
                            ORDER BY rolname
                            """)) {
                while (rows.next()) {
                    roles.add(rows.getString(1));
                }
            }
            applicationRoles = List.copyOf(roles);
            assertThat(applicationRoles).contains("app_api", "app_worker", "app_txn_examentry");
            try (var statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE audit.hardening_parent (tenant_id uuid PRIMARY KEY)");
                statement.execute(
                        "INSERT INTO audit.hardening_parent VALUES ('"
                                + AuditPostgreSqlFixture.TENANT_ID
                                + "')");
                statement.execute("GRANT SELECT, DELETE ON audit.hardening_parent TO app_delivery");
            }
        }
    }

    Stream<Arguments> roleMutations() {
        return applicationRoles.stream()
                .flatMap(
                        role ->
                                Stream.of(
                                        Arguments.of(
                                                role,
                                                "UPDATE",
                                                "UPDATE audit.audit_event SET payload = '{}'::jsonb"),
                                        Arguments.of(
                                                role, "DELETE", "DELETE FROM audit.audit_event")));
    }

    @ParameterizedTest(name = "{0} cannot {1} audit events")
    @MethodSource("roleMutations")
    void everyApplicationRoleLacksMutationPrivileges(String role, String operation, String sql)
            throws SQLException {
        try (Connection connection = ownerConnection();
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                try (var check =
                        connection.prepareStatement(
                                "SELECT has_table_privilege(?, 'audit.audit_event', ?)")) {
                    check.setString(1, role);
                    check.setString(2, operation);
                    try (var rows = check.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getBoolean(1)).isFalse();
                    }
                }
                statement.execute(
                        "SET LOCAL app.tenant_id = '" + AuditPostgreSqlFixture.TENANT_ID + "'");
                statement.execute("SET LOCAL ROLE " + quoteIdentifier(role));
                SQLException failure =
                        assertThrows(SQLException.class, () -> statement.executeUpdate(sql));
                assertThat(failure.getSQLState()).isEqualTo("42501");
            } finally {
                connection.rollback();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE audit.audit_event SET payload = '{}'::jsonb",
                "DELETE FROM audit.audit_event"
            })
    void immutableTriggerRefusesMutationEvenWithPrivileges(String sql) throws SQLException {
        try (Connection connection = ownerConnection();
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute(
                        "GRANT SELECT, UPDATE, DELETE ON audit.audit_event TO app_delivery");
                statement.execute(
                        "SET LOCAL app.tenant_id = '" + AuditPostgreSqlFixture.TENANT_ID + "'");
                statement.execute("SET LOCAL ROLE app_delivery");
                try (var rows = statement.executeQuery("SELECT count(*) FROM audit.audit_event")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(24);
                }
                SQLException failure =
                        assertThrows(SQLException.class, () -> statement.executeUpdate(sql));
                assertThat(failure.getSQLState()).isEqualTo("23000");
                assertThat(failure.getMessage()).contains("audit events are immutable");
            } finally {
                connection.rollback();
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM audit.audit_event")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(24);
            }
        }
    }

    @ParameterizedTest(name = "mapped entity delete with DELETE privilege = {0}")
    @ValueSource(booleans = {false, true})
    void mappedEntityDeletionIsRefused(boolean grantDelete) throws SQLException {
        var factory = reactiveFactory();
        var template = new R2dbcEntityTemplate(factory);
        var client = template.getDatabaseClient();
        var transaction = TransactionalOperator.create(new R2dbcTransactionManager(factory));
        var attempt =
                client.sql(deletionGrants(grantDelete))
                        .then()
                        .then(client.sql(tenantContext()).then())
                        .then(client.sql("SET LOCAL ROLE app_delivery").then())
                        .then(template.select(MappedAuditEvent.class).count())
                        .doOnNext(count -> assertThat(count).isEqualTo(24))
                        .then(template.select(MappedAuditEvent.class).first())
                        .flatMap(template::delete)
                        .as(transaction::transactional);

        StepVerifier.create(attempt)
                .expectErrorSatisfies(
                        failure -> {
                            Throwable cause = failure;
                            while (cause.getCause() != null) {
                                cause = cause.getCause();
                            }
                            assertThat(cause).isInstanceOf(R2dbcException.class);
                            assertThat(((R2dbcException) cause).getSqlState())
                                    .isEqualTo(grantDelete ? "23000" : "42501");
                            if (grantDelete) {
                                assertThat(cause)
                                        .hasMessageContaining("audit events are immutable");
                            }
                        })
                .verify(Duration.ofSeconds(30));
        assertEvidenceSurvives();
    }

    @ParameterizedTest(name = "native delete with DELETE privilege = {0}")
    @ValueSource(booleans = {false, true})
    void nativeDeletionIsRefused(boolean grantDelete) throws SQLException {
        try (Connection connection = ownerConnection()) {
            connection.setAutoCommit(false);
            try {
                prepareDeletion(connection, grantDelete);
                try (var statement =
                        connection.prepareStatement(
                                "DELETE FROM audit.audit_event WHERE tenant_id = ?")) {
                    statement.setObject(1, AuditPostgreSqlFixture.TENANT_ID);
                    assertDeletionRefused(
                            assertThrows(SQLException.class, statement::executeUpdate),
                            grantDelete);
                }
            } finally {
                connection.rollback();
            }
        }
        assertEvidenceSurvives();
    }

    @ParameterizedTest(name = "batch delete with DELETE privilege = {0}")
    @ValueSource(booleans = {false, true})
    void batchDeletionIsRefused(boolean grantDelete) throws SQLException {
        try (Connection connection = ownerConnection()) {
            connection.setAutoCommit(false);
            try {
                prepareDeletion(connection, grantDelete);
                var ids = new ArrayList<UUID>();
                try (var statement = connection.createStatement();
                        var rows =
                                statement.executeQuery(
                                        "SELECT audit_event_id FROM audit.audit_event"
                                                + " ORDER BY audit_event_id LIMIT 2")) {
                    while (rows.next()) {
                        ids.add(rows.getObject(1, UUID.class));
                    }
                }
                assertThat(ids).hasSize(2);
                try (var statement =
                        connection.prepareStatement(
                                "DELETE FROM audit.audit_event WHERE audit_event_id = ?")) {
                    for (UUID id : ids) {
                        statement.setObject(1, id);
                        statement.addBatch();
                    }
                    assertDeletionRefused(
                            assertThrows(SQLException.class, statement::executeBatch), grantDelete);
                }
            } finally {
                connection.rollback();
            }
        }
        assertEvidenceSurvives();
    }

    @Test
    void parentCascadeDeletionIsRefusedByTrigger() throws SQLException {
        try (Connection connection = ownerConnection();
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                // Model an accidentally introduced cascade without changing production migrations.
                statement.execute(
                        """
                        ALTER TABLE audit.audit_event ADD CONSTRAINT hardening_parent_fk
                        FOREIGN KEY (tenant_id) REFERENCES audit.hardening_parent (tenant_id)
                        ON DELETE CASCADE
                        """);
                var beforeDelete = connection.setSavepoint();
                prepareDeletion(connection, false);
                assertDeletionRefused(
                        assertThrows(
                                SQLException.class,
                                () ->
                                        statement.executeUpdate(
                                                "DELETE FROM audit.hardening_parent")),
                        true);
                connection.rollback(beforeDelete);
                try (var rows =
                        statement.executeQuery("SELECT count(*) FROM audit.hardening_parent")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(1);
                }
            } finally {
                connection.rollback();
            }
        }
        assertEvidenceSurvives();
    }

    Stream<Arguments> anchorPrivileges() {
        return applicationRoles.stream()
                .flatMap(
                        role ->
                                Stream.of(
                                                "audit_chain_root_head",
                                                "audit_chain_checkpoint",
                                                "audit_chain_seal")
                                        .flatMap(
                                                table ->
                                                        Stream.of(
                                                                        "SELECT",
                                                                        "INSERT",
                                                                        "UPDATE",
                                                                        "DELETE",
                                                                        "TRUNCATE",
                                                                        "REFERENCES",
                                                                        "TRIGGER")
                                                                .map(
                                                                        privilege ->
                                                                                Arguments.of(
                                                                                        role, table,
                                                                                        privilege))));
    }

    @ParameterizedTest(name = "{0}: {1} {2}")
    @MethodSource("anchorPrivileges")
    void anchorPrivilegesMatchApprovedMinimum(String role, String table, String privilege)
            throws SQLException {
        boolean permitted =
                role.equals("app_audit_sealer")
                        && switch (table) {
                            case "audit_chain_root_head" ->
                                    privilege.equals("SELECT") || privilege.equals("UPDATE");
                            case "audit_chain_checkpoint" -> privilege.equals("INSERT");
                            case "audit_chain_seal" ->
                                    privilege.equals("SELECT") || privilege.equals("INSERT");
                            default -> false;
                        };
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement("SELECT has_table_privilege(?, ?, ?)")) {
            statement.setString(1, role);
            statement.setString(2, "audit." + table);
            statement.setString(3, privilege);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBoolean(1)).isEqualTo(permitted);
            }
        }
    }

    @Test
    void complianceAdapterFiltersAndPaginatesRealRows() {
        TenantId tenant = TenantId.parse(AuditPostgreSqlFixture.TENANT_ID.toString());
        var queries = new R2dbcComplianceAuditQueries();
        Instant asOf = Instant.parse("2026-04-01T00:00:00Z");
        Request timeline = complianceRequest(Optional.empty(), Optional.empty(), Optional.empty());
        Mono<Void> assertions =
                queries.find(tenant, timeline, asOf, Optional.empty(), 501)
                        .flatMap(
                                all -> {
                                    assertThat(all).hasSize(24);
                                    var last = all.get(4);
                                    var cursor =
                                            new ComplianceCursor(
                                                    1,
                                                    tenant.toString(),
                                                    timeline.filterFingerprint(),
                                                    asOf,
                                                    last.occurredAt(),
                                                    last.retentionClass(),
                                                    last.shardId(),
                                                    last.sequence());
                                    return queries.find(tenant, timeline, asOf, Optional.empty(), 5)
                                            .doOnNext(
                                                    page ->
                                                            assertThat(page)
                                                                    .isEqualTo(all.subList(0, 5)))
                                            .then(
                                                    queries.find(
                                                            tenant,
                                                            timeline,
                                                            asOf,
                                                            Optional.of(cursor),
                                                            501))
                                            .doOnNext(
                                                    page ->
                                                            assertThat(page)
                                                                    .isEqualTo(all.subList(5, 24)));
                                })
                        .then(
                                queries.find(
                                        tenant,
                                        complianceRequest(
                                                Optional.of("fixture"),
                                                Optional.of("fixture-entity-0-0"),
                                                Optional.empty()),
                                        asOf,
                                        Optional.empty(),
                                        501))
                        .doOnNext(
                                rows ->
                                        assertThat(rows)
                                                .hasSize(3)
                                                .allMatch(
                                                        row ->
                                                                row.entityId()
                                                                        .equals(
                                                                                "fixture-entity-0-0")))
                        .then(
                                queries.find(
                                        tenant,
                                        complianceRequest(
                                                Optional.empty(),
                                                Optional.empty(),
                                                Optional.of("audit.FIXTURE_EVENT.v1")),
                                        asOf,
                                        Optional.empty(),
                                        501))
                        .doOnNext(rows -> assertThat(rows).hasSize(24))
                        .then(
                                queries.find(
                                        tenant,
                                        complianceRequest(
                                                Optional.of("fixture"),
                                                Optional.of("' OR 1=1 --"),
                                                Optional.empty()),
                                        asOf,
                                        Optional.empty(),
                                        501))
                        .doOnNext(rows -> assertThat(rows).isEmpty())
                        .then();
        StepVerifier.create(inReaderFixture(tenant, connection -> assertions)).verifyComplete();
    }

    @Test
    void auditRlsHidesForeignRowsEvenWithoutTenantPredicate() {
        TenantId owner = TenantId.parse(AuditPostgreSqlFixture.TENANT_ID.toString());
        TenantId other = TenantId.parse("00000000-0000-0000-0000-000000000072");
        Function<io.r2dbc.spi.Connection, Mono<Long>> unscopedRead =
                connection ->
                        Flux.from(
                                        connection
                                                .createStatement(
                                                        "SELECT count(*) AS count FROM audit.audit_event")
                                                .execute())
                                .flatMap(
                                        result ->
                                                result.map(
                                                        (row, metadata) ->
                                                                java.util.Objects.requireNonNull(
                                                                        row.get(
                                                                                "count",
                                                                                Long.class))))
                                .single();
        StepVerifier.create(inReaderFixture(owner, unscopedRead)).expectNext(24L).verifyComplete();
        StepVerifier.create(inReaderFixture(other, unscopedRead)).expectNext(0L).verifyComplete();
        // Deliberately pass the victim tenant to the adapter; database scope must still win.
        StepVerifier.create(
                        inReaderFixture(
                                other,
                                connection ->
                                        new R2dbcComplianceAuditQueries()
                                                .find(
                                                        owner,
                                                        complianceRequest(
                                                                Optional.empty(),
                                                                Optional.empty(),
                                                                Optional.empty()),
                                                        Instant.parse("2026-04-01T00:00:00Z"),
                                                        Optional.empty(),
                                                        501)))
                .assertNext(rows -> assertThat(rows).isEmpty())
                .verifyComplete();
    }

    private <T> Mono<T> inReaderFixture(
            TenantId tenant, Function<io.r2dbc.spi.Connection, Mono<T>> work) {
        // A rolled-back SELECT grant isolates RLS proof; this is not production role wiring.
        return Mono.usingWhen(
                Mono.from(reactiveFactory().create()),
                connection ->
                        Mono.from(connection.beginTransaction())
                                .then(
                                        execute(
                                                connection,
                                                "GRANT SELECT ON audit.audit_event TO app_delivery"))
                                .then(execute(connection, "SET LOCAL ROLE app_delivery"))
                                .then(
                                        execute(
                                                connection,
                                                "SET LOCAL app.tenant_id = '" + tenant + "'"))
                                .then(Mono.defer(() -> work.apply(connection)))
                                .contextWrite(
                                        context ->
                                                context.put(
                                                        TransactionalConnection.class,
                                                        (TransactionalConnection)
                                                                connection::createStatement)),
                AuditStoreHardeningIntegrationTest::rollbackAndClose,
                (connection, failure) -> rollbackAndClose(connection),
                AuditStoreHardeningIntegrationTest::rollbackAndClose);
    }

    private static Mono<Void> execute(io.r2dbc.spi.Connection connection, String sql) {
        return Flux.from(connection.createStatement(sql).execute())
                .flatMap(io.r2dbc.spi.Result::getRowsUpdated)
                .then();
    }

    private static Mono<Void> rollbackAndClose(io.r2dbc.spi.Connection connection) {
        return Mono.from(connection.rollbackTransaction()).then(Mono.from(connection.close()));
    }

    private ConnectionFactory reactiveFactory() {
        return ConnectionFactories.get(
                ConnectionFactoryOptions.builder()
                        .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                        .option(ConnectionFactoryOptions.HOST, postgres.getHost())
                        .option(ConnectionFactoryOptions.PORT, postgres.getMappedPort(5432))
                        .option(ConnectionFactoryOptions.DATABASE, database)
                        .option(ConnectionFactoryOptions.USER, postgres.getUsername())
                        .option(ConnectionFactoryOptions.PASSWORD, postgres.getPassword())
                        .build());
    }

    @Test
    void complianceAdapterAppliesInclusiveFromAndExclusiveTo() {
        TenantId tenant = TenantId.parse(AuditPostgreSqlFixture.TENANT_ID.toString());
        Request request =
                new Request(
                        Optional.of(Instant.parse("2026-03-01T00:00:00Z")),
                        Optional.of(Instant.parse("2026-03-01T00:00:01Z")),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        100);
        StepVerifier.create(
                        inReaderFixture(
                                tenant,
                                connection ->
                                        new R2dbcComplianceAuditQueries()
                                                .find(
                                                        tenant,
                                                        request,
                                                        Instant.parse("2026-04-01T00:00:00Z"),
                                                        Optional.empty(),
                                                        101)))
                .assertNext(
                        rows ->
                                assertThat(rows)
                                        .hasSize(4)
                                        .allMatch(
                                                row ->
                                                        row.occurredAt()
                                                                .equals(
                                                                        request.occurredFrom()
                                                                                .orElseThrow())))
                .verifyComplete();
    }

    private static Request complianceRequest(
            Optional<String> entityType, Optional<String> entityId, Optional<String> eventType) {
        return new Request(
                Optional.empty(),
                Optional.empty(),
                entityType,
                entityId,
                eventType,
                Optional.empty(),
                100);
    }

    private static void prepareDeletion(Connection connection, boolean grantDelete)
            throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(deletionGrants(grantDelete));
            statement.execute(tenantContext());
            statement.execute("SET LOCAL ROLE app_delivery");
            try (var rows = statement.executeQuery("SELECT count(*) FROM audit.audit_event")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(24);
            }
        }
    }

    private static String deletionGrants(boolean grantDelete) {
        return "GRANT "
                + (grantDelete ? "SELECT, DELETE" : "SELECT")
                + " ON audit.audit_event TO app_delivery";
    }

    private static String tenantContext() {
        return "SET LOCAL app.tenant_id = '" + AuditPostgreSqlFixture.TENANT_ID + "'";
    }

    private static void assertDeletionRefused(SQLException failure, boolean triggerExpected) {
        assertThat(failure.getSQLState()).isEqualTo(triggerExpected ? "23000" : "42501");
        if (triggerExpected) {
            assertThat(failure.getMessage()).contains("audit events are immutable");
        }
    }

    private void assertEvidenceSurvives() throws SQLException {
        try (Connection connection = ownerConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM audit.audit_event")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(24);
        }
    }

    @Table(name = "audit_event", schema = "audit")
    record MappedAuditEvent(@Id UUID auditEventId) {}

    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword());
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
