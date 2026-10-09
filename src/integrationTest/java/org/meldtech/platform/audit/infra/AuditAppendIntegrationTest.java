package org.meldtech.platform.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.meldtech.platform.PostgreSqlTestContainer;
import org.meldtech.platform.audit.domain.AuditChainKey;
import org.meldtech.platform.audit.domain.AuditHashing;
import org.meldtech.platform.audit.domain.CanonicalAuditEnvelope;
import org.meldtech.platform.audit.domain.CanonicalJsonCodec;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.audit.domain.RetentionResolver;
import org.meldtech.platform.migration.MigrationApplication;
import org.meldtech.platform.platform.api.AuditStatementKind;
import org.meldtech.platform.platform.api.TransactionalCollaboration;
import org.meldtech.platform.platform.api.TransactionalConnection;
import org.meldtech.platform.platform.infra.persistence.AuditTransactionTestSupport;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.EntityRef;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.audit.RetentionDecision;
import org.meldtech.platform.shared.kernel.audit.RetentionHorizon;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditAppendIntegrationTest {
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-08T00:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private PostgreSQLContainer postgres;
    private String jdbcUrl;
    private TransactionalCollaboration transactions;

    @BeforeAll
    void migrate() throws Exception {
        postgres = PostgreSqlTestContainer.instance();
        postgres.start();
        String database = "audit_append_" + UUID.randomUUID().toString().replace("-", "");
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
        try (Connection connection = ownerConnection();
                var statement = connection.createStatement()) {
            statement.execute(
                    """
                    CREATE TABLE delivery.audit_business_probe (
                        tenant_id uuid NOT NULL, probe_id uuid PRIMARY KEY);
                    GRANT INSERT, SELECT ON delivery.audit_business_probe TO app_txn_examentry;
                    ALTER TABLE delivery.audit_business_probe ENABLE ROW LEVEL SECURITY;
                    CREATE POLICY tenant_scope ON delivery.audit_business_probe
                        USING (tenant_id = current_setting('app.tenant_id')::uuid)
                        WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);
                    SELECT audit.provision_audit_month('2026-10-01'::date);
                    """);
        }
        transactions =
                AuditTransactionTestSupport.collaboration(
                        ConnectionFactories.get(
                                ConnectionFactoryOptions.builder()
                                        .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                                        .option(ConnectionFactoryOptions.HOST, postgres.getHost())
                                        .option(
                                                ConnectionFactoryOptions.PORT,
                                                postgres.getMappedPort(5432))
                                        .option(ConnectionFactoryOptions.DATABASE, database)
                                        .option(
                                                ConnectionFactoryOptions.USER,
                                                postgres.getUsername())
                                        .option(
                                                ConnectionFactoryOptions.PASSWORD,
                                                postgres.getPassword())
                                        .build()));
    }

    @Test
    void auditEmissionFailureRollsBackBusinessChange() throws SQLException {
        TenantId tenant = provisionTenant();
        UUID duplicateEventId = UUID.randomUUID();
        R2dbcAuditEmitter emitter = emitter(duplicateEventId);
        StepVerifier.create(write(tenant, emitter, false)).verifyComplete();
        String committedHead = head(tenant);

        StepVerifier.create(write(tenant, emitter, false))
                .expectErrorSatisfies(
                        failure ->
                                assertThat(failure)
                                        .isInstanceOf(io.r2dbc.spi.R2dbcException.class)
                                        .extracting(
                                                error ->
                                                        ((io.r2dbc.spi.R2dbcException) error)
                                                                .getSqlState())
                                        .isEqualTo("23505"))
                .verify(TIMEOUT);

        assertThat(count("delivery.audit_business_probe", tenant)).isEqualTo(1);
        assertThat(count("audit.audit_event", tenant)).isEqualTo(1);
        assertThat(head(tenant)).isEqualTo(committedHead);
    }

    @Test
    void businessRollbackDiscardsAuditRecordAndHeadAdvance() throws SQLException {
        TenantId tenant = provisionTenant();
        String initialHead = head(tenant);
        StepVerifier.create(write(tenant, emitter(UUID.randomUUID()), true))
                .expectErrorMessage("injected business rollback")
                .verify(TIMEOUT);
        assertThat(count("delivery.audit_business_probe", tenant)).isZero();
        assertThat(count("audit.audit_event", tenant)).isZero();
        assertThat(head(tenant)).isEqualTo(initialHead);
    }

    private Mono<Void> write(TenantId tenant, R2dbcAuditEmitter emitter, boolean rollback) {
        return transactions.inExamEntryTransaction(
                tenant,
                connection ->
                        businessInsert(connection, tenant)
                                .then(Mono.from(emitter.emit(event(), actor(tenant), OCCURRED_AT)))
                                .then(
                                        rollback
                                                ? Mono.error(
                                                        new IllegalStateException(
                                                                "injected business rollback"))
                                                : Mono.empty()));
    }

    @Test
    void capturesExactlyTwoAuditStatementsInProtocolOrder() throws SQLException {
        TenantId tenant = provisionTenant();
        List<String> statements = new ArrayList<>();
        StepVerifier.create(
                        transactions.inExamEntryTransaction(
                                tenant,
                                connection -> {
                                    TransactionalConnection observed =
                                            new RecordingConnection(connection, statements);
                                    return businessInsert(observed, tenant)
                                            .then(
                                                    Mono.from(
                                                            emitter(UUID.randomUUID())
                                                                    .emit(
                                                                            event(),
                                                                            actor(tenant),
                                                                            OCCURRED_AT)))
                                            .contextWrite(
                                                    context ->
                                                            context.put(
                                                                    TransactionalConnection.class,
                                                                    observed));
                                }))
                .verifyComplete();
        assertThat(statements).hasSize(3);
        assertThat(statements.get(0)).startsWith("INSERT INTO delivery.audit_business_probe");
        assertThat(statements.subList(1, 3))
                .containsExactly(
                        R2dbcAuditAppendRepository.LOCK_HEAD_SQL,
                        R2dbcAuditAppendRepository.APPEND_SQL);
        assertThat(count("audit.audit_event", tenant)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(FailurePoint.class)
    void failureAtEveryProtocolBoundaryRollsBackAllState(FailurePoint point) throws SQLException {
        TenantId tenant = provisionTenant();
        String initialHead = head(tenant);
        AuditAppendStore delegate = new R2dbcAuditAppendRepository();
        AuditAppendStore failing =
                new AuditAppendStore() {
                    @Override
                    public Mono<LockedChainHead> lockHead(
                            TransactionalConnection connection, AuditChainKey key) {
                        return delegate.lockHead(connection, key)
                                .flatMap(
                                        head ->
                                                point == FailurePoint.AFTER_LOCK
                                                        ? Mono.error(
                                                                new IllegalStateException(
                                                                        point.name()))
                                                        : Mono.just(head));
                    }

                    @Override
                    public Mono<Void> appendAndAdvance(
                            TransactionalConnection connection,
                            LockedChainHead head,
                            PreparedAuditRecord record) {
                        return delegate.appendAndAdvance(connection, head, record)
                                .then(
                                        point == FailurePoint.AFTER_CTE
                                                ? Mono.error(
                                                        new IllegalStateException(point.name()))
                                                : Mono.empty());
                    }
                };
        StepVerifier.create(
                        transactions.inExamEntryTransaction(
                                tenant,
                                connection ->
                                        businessInsert(connection, tenant)
                                                .then(
                                                        Mono.from(
                                                                emitter(UUID.randomUUID(), failing)
                                                                        .emit(
                                                                                event(),
                                                                                actor(tenant),
                                                                                OCCURRED_AT)))
                                                .then(
                                                        point == FailurePoint.BEFORE_COMMIT
                                                                ? Mono.error(
                                                                        new IllegalStateException(
                                                                                point.name()))
                                                                : Mono.empty())))
                .expectErrorMessage(point.name())
                .verify(TIMEOUT);
        assertThat(count("delivery.audit_business_probe", tenant)).isZero();
        assertThat(count("audit.audit_event", tenant)).isZero();
        assertThat(head(tenant)).isEqualTo(initialHead);
    }

    @Test
    void laterBusinessSqlIsRefusedAndRollsBackTheAppend() throws SQLException {
        TenantId tenant = provisionTenant();
        String initialHead = head(tenant);
        StepVerifier.create(
                        transactions.inExamEntryTransaction(
                                tenant,
                                connection ->
                                        businessInsert(connection, tenant)
                                                .then(
                                                        Mono.from(
                                                                emitter(UUID.randomUUID())
                                                                        .emit(
                                                                                event(),
                                                                                actor(tenant),
                                                                                OCCURRED_AT)))
                                                .then(businessInsert(connection, tenant))))
                .expectErrorMessage("Business SQL is forbidden after audit finalization begins")
                .verify(TIMEOUT);
        assertThat(count("delivery.audit_business_probe", tenant)).isZero();
        assertThat(count("audit.audit_event", tenant)).isZero();
        assertThat(head(tenant)).isEqualTo(initialHead);
    }

    @Test
    void sameShardWritersSerializeBeforeDerivingTheirPredecessors() throws Exception {
        TenantId tenant = provisionTenant();
        CompletableFuture<Void> firstLocked = new CompletableFuture<>();
        Sinks.Empty<Void> release = Sinks.empty();
        List<PreparedAuditRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();
        AuditAppendStore firstStore = observingStore(records, firstLocked, release.asMono());
        CompletableFuture<Void> first =
                write(tenant, emitter(UUID.randomUUID(), firstStore), false).toFuture();
        CompletableFuture<Void> second = null;
        try {
            firstLocked.get(10, TimeUnit.SECONDS);
            second =
                    write(
                                    tenant,
                                    emitter(
                                            UUID.randomUUID(),
                                            observingStore(
                                                    records,
                                                    new CompletableFuture<>(),
                                                    Mono.empty())),
                                    false)
                            .toFuture();
            awaitBlockedPredecessorLock();
            assertThat(second.isDone()).isFalse();
            assertThat(release.tryEmitEmpty()).isEqualTo(Sinks.EmitResult.OK);
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            release.tryEmitEmpty();
            if (!first.isDone()) {
                first.cancel(true);
            }
            if (second != null && !second.isDone()) {
                second.cancel(true);
            }
        }
        assertThat(records).extracting(PreparedAuditRecord::sequence).containsExactly(1L, 2L);
        AuditChainKey chain =
                new AuditChainKey(
                        tenant,
                        new EpochIdentity(
                                RetentionClass.GENERAL_AUDIT_EVENT, YearMonth.of(2026, 10)),
                        0,
                        1);
        assertThat(records.getFirst().previousHash())
                .isEqualTo(
                        AuditHashing.chainSeed(
                                tenant,
                                chain.epoch().retentionClass(),
                                chain.epoch().period(),
                                0,
                                1,
                                new CanonicalJsonCodec()));
        assertThat(records.get(1).previousHash()).isEqualTo(records.getFirst().recordHash());
        for (PreparedAuditRecord record : records) {
            assertThat(record.recordHash())
                    .isEqualTo(
                            AuditHashing.recordHash(
                                    record.previousHash(),
                                    new CanonicalJsonCodec()
                                            .encode(
                                                    CanonicalAuditEnvelope.create(
                                                            record.event(),
                                                            record.actor(),
                                                            record.occurredAt(),
                                                            record.retention(),
                                                            record.chain(),
                                                            record.sequence(),
                                                            record.recordHash()
                                                                    .hashAlgorithmVersion()))));
        }
        assertThat(count("delivery.audit_business_probe", tenant)).isEqualTo(2);
        assertThat(count("audit.audit_event", tenant)).isEqualTo(2);
        assertPersistedChain(tenant, records);
    }

    private static AuditAppendStore observingStore(
            List<PreparedAuditRecord> records, CompletableFuture<Void> locked, Mono<Void> release) {
        AuditAppendStore delegate = new R2dbcAuditAppendRepository();
        return new AuditAppendStore() {
            @Override
            public Mono<LockedChainHead> lockHead(
                    TransactionalConnection connection, AuditChainKey key) {
                return delegate.lockHead(connection, key)
                        .flatMap(
                                head -> {
                                    locked.complete(null);
                                    return release.thenReturn(head);
                                });
            }

            @Override
            public Mono<Void> appendAndAdvance(
                    TransactionalConnection connection,
                    LockedChainHead head,
                    PreparedAuditRecord record) {
                return delegate.appendAndAdvance(connection, head, record)
                        .doOnSuccess(ignored -> records.add(record));
            }
        };
    }

    private void awaitBlockedPredecessorLock() throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection connection = ownerConnection();
                    var statement = connection.createStatement();
                    var rows =
                            statement.executeQuery(
                                    """
                            SELECT count(*) FROM pg_stat_activity
                            WHERE datname = current_database() AND wait_event_type = 'Lock'
                              AND query LIKE '%audit.audit_chain_head%' AND query LIKE '%FOR UPDATE%'
                            """)) {
                rows.next();
                if (rows.getInt(1) > 0) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Second writer never waited on the PostgreSQL predecessor lock");
    }

    private void assertPersistedChain(TenantId tenant, List<PreparedAuditRecord> records)
            throws SQLException {
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement(
                                """
                                SELECT seq, prev_hash, record_hash FROM audit.audit_event
                                WHERE tenant_id = ? ORDER BY seq
                                """)) {
            statement.setObject(1, UUID.fromString(tenant.toString()));
            try (var rows = statement.executeQuery()) {
                for (PreparedAuditRecord record : records) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(record.sequence());
                    assertThat(rows.getBytes(2)).isEqualTo(record.previousHash().bytes());
                    assertThat(rows.getBytes(3)).isEqualTo(record.recordHash().bytes());
                }
                assertThat(rows.next()).isFalse();
            }
        }
        assertThat(head(tenant))
                .isEqualTo(
                        "2:"
                                + java.util.HexFormat.of()
                                        .formatHex(records.getLast().recordHash().bytes()));
    }

    private enum FailurePoint {
        AFTER_LOCK,
        AFTER_CTE,
        BEFORE_COMMIT
    }

    private record RecordingConnection(TransactionalConnection delegate, List<String> statements)
            implements TransactionalConnection {
        @Override
        public Statement createStatement(String sql) {
            statements.add(sql);
            return delegate.createStatement(sql);
        }

        @Override
        public void beginAuditFinalization() {
            delegate.beginAuditFinalization();
        }

        @Override
        public Statement createAuditStatement(AuditStatementKind kind, String sql) {
            statements.add(sql);
            return delegate.createAuditStatement(kind, sql);
        }
    }

    private static Mono<Void> businessInsert(TransactionalConnection connection, TenantId tenant) {
        return Mono.defer(
                () ->
                        Flux.from(
                                        connection
                                                .createStatement(
                                                        "INSERT INTO delivery.audit_business_probe"
                                                                + " (tenant_id, probe_id) VALUES ($1, $2)")
                                                .bind(0, UUID.fromString(tenant.toString()))
                                                .bind(1, UUID.randomUUID())
                                                .execute())
                                .flatMap(Result::getRowsUpdated)
                                .then());
    }

    private static R2dbcAuditEmitter emitter(UUID eventId) {
        return emitter(eventId, new R2dbcAuditAppendRepository());
    }

    private static R2dbcAuditEmitter emitter(UUID eventId, AuditAppendStore store) {
        RetentionDecision decision =
                new RetentionDecision(
                        "audit.default",
                        1,
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Optional.empty(),
                        Map.of(
                                RetentionClass.GENERAL_AUDIT_EVENT,
                                RetentionHorizon.until(Instant.parse("2028-10-08T00:00:00Z"))),
                        RetentionClass.GENERAL_AUDIT_EVENT);
        return new R2dbcAuditEmitter(
                new RetentionResolver((time, type, entity, candidates) -> decision),
                (tenant, period) -> 1,
                () -> eventId,
                new CanonicalJsonCodec(),
                store);
    }

    private static AuditEvent event() {
        return new AuditEvent(
                "platform.OUTBOX_REDRIVE_COMPLETED.v1",
                new EntityRef("outbox.event", "same-entity"),
                Set.of(RetentionClass.GENERAL_AUDIT_EVENT),
                new ObjectValue(Map.of()));
    }

    private static ActorContext actor(TenantId tenant) {
        return ActorContext.tenantWorkforce(
                new ActorId("audit-test"),
                tenant,
                CorrelationId.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                SourceIp.parse("127.0.0.1"));
    }

    private TenantId provisionTenant() throws SQLException {
        TenantId tenant = TenantId.parse(UUID.randomUUID().toString());
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement(
                                """
                                SELECT audit.provision_audit_epoch_heads(
                                    ?, 'GENERAL_AUDIT_EVENT', '2026-10-01', 1, 1::smallint)
                                """)) {
            statement.setObject(1, UUID.fromString(tenant.toString()));
            statement.execute();
        }
        return tenant;
    }

    private int count(String table, TenantId tenant) throws SQLException {
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT count(*) FROM " + table + " WHERE tenant_id = ?")) {
            statement.setObject(1, UUID.fromString(tenant.toString()));
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    private String head(TenantId tenant) throws SQLException {
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement(
                                """
                                SELECT seq || ':' || encode(head_hash, 'hex')
                                FROM audit.audit_chain_head WHERE tenant_id = ?
                                """)) {
            statement.setObject(1, UUID.fromString(tenant.toString()));
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword());
    }
}
