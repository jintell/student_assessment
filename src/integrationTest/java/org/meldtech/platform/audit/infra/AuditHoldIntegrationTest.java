package org.meldtech.platform.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.meldtech.platform.PostgreSqlTestContainer;
import org.meldtech.platform.audit.application.ActiveLegalHold;
import org.meldtech.platform.audit.application.AuditHoldService;
import org.meldtech.platform.audit.application.DispositionRequest;
import org.meldtech.platform.audit.application.ResumedDisposition;
import org.meldtech.platform.audit.domain.CanonicalJsonCodec;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.audit.domain.RetentionResolver;
import org.meldtech.platform.audit.testing.AuditPostgreSqlFixture;
import org.meldtech.platform.audit.testing.AuditPostgreSqlFixture.EventSeed;
import org.meldtech.platform.migration.MigrationApplication;
import org.meldtech.platform.platform.api.TransactionalConnection;
import org.meldtech.platform.shared.kernel.audit.AuditEmitter;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.audit.RetentionDecision;
import org.meldtech.platform.shared.kernel.audit.RetentionHorizon;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditHoldIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final TenantId TENANT_ID =
            TenantId.parse(AuditPostgreSqlFixture.TENANT_ID.toString());
    private static final YearMonth EVIDENCE_PERIOD = YearMonth.of(2035, 1);
    private static final Instant DETECTED_AT = Instant.parse("2035-01-10T12:00:00Z");
    private static final Instant RELEASED_AT = Instant.parse("2035-01-15T12:00:00Z");
    private static final UUID REQUEST_ID = UUID.fromString("019dca1d-c600-7000-8000-000000000021");
    private static final UUID ROLLED_BACK_REQUEST_ID =
            UUID.fromString("019dca1d-c600-7000-8000-000000000022");

    private PostgreSQLContainer postgres;
    private String jdbcUrl;
    private ConnectionFactory connectionFactory;
    private AuditPostgreSqlFixture.Manifest fixture;

    @BeforeAll
    void migrateAndSeedFixture() throws Exception {
        postgres = PostgreSqlTestContainer.instance();
        postgres.start();
        String database = "audit_hold_" + UUID.randomUUID().toString().replace("-", "");
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
        migrateDatabase();
        connectionFactory = connectionFactory(database);
        try (Connection connection = ownerConnection()) {
            fixture = AuditPostgreSqlFixture.seed(connection);
            provisionEvidenceEpoch(connection);
        }
    }

    @Test
    void holdSuppressionAndReleasePreserveTheOriginalDispositionClock() throws SQLException {
        R2dbcAuditHoldRepository repository = new R2dbcAuditHoldRepository();
        AuditHoldService service = new AuditHoldService(repository, emitter());
        DispositionRequest request = heldRequest(REQUEST_ID);
        ActiveLegalHold hold = activeHold();

        AuditHoldService failingSuppression =
                new AuditHoldService(
                        repository,
                        (event, actor, occurredAt) ->
                                Mono.error(new IllegalStateException("suppression audit failed")));
        StepVerifier.create(
                        inTransaction(
                                ignored ->
                                        failingSuppression.suspendIfHeld(
                                                heldRequest(ROLLED_BACK_REQUEST_ID),
                                                List.of(hold),
                                                actor(),
                                                DETECTED_AT)))
                .expectErrorMessage("suppression audit failed")
                .verify(TIMEOUT);
        assertThat(lifecycleCount(ROLLED_BACK_REQUEST_ID)).isZero();

        StepVerifier.create(
                        inTransaction(
                                ignored ->
                                        service.suspendIfHeld(
                                                request, List.of(hold), actor(), DETECTED_AT)))
                .expectNext(AuditHoldService.HoldResult.HOLD_SUSPENDED)
                .expectComplete()
                .verify(TIMEOUT);
        StepVerifier.create(
                        inTransaction(
                                ignored ->
                                        service.suspendIfHeld(
                                                request, List.of(hold), actor(), DETECTED_AT)))
                .expectNext(AuditHoldService.HoldResult.HOLD_SUSPENDED)
                .expectComplete()
                .verify(TIMEOUT);

        LifecycleRow suspended = lifecycle(REQUEST_ID);
        assertThat(suspended.state()).isEqualTo("HOLD_SUSPENDED");
        assertThat(suspended.originalRetentionStart()).isEqualTo(request.originalRetentionStart());
        assertThat(suspended.originalDueAt()).isEqualTo(request.dueAt());
        assertThat(suspended.holdReferences())
                .contains("\"hold_reference\": \"hold-audit-fixture-001\"")
                .contains("\"legal_basis_reference\": \"legal-basis-7\"");
        assertThat(suspended.detectedAt()).isEqualTo(DETECTED_AT);
        assertThat(suspended.releasedAt()).isNull();
        assertThat(auditEventCount(AuditHoldService.SUPPRESSED_EVENT)).isEqualTo(1);
        assertSuppressionPayload(request);
        assertCoveredPartitionRemainsAttached();

        DispositionRequest resetClock =
                new DispositionRequest(
                        request.requestId(),
                        request.tenantId(),
                        request.epoch(),
                        request.policyKey(),
                        request.policyVersion(),
                        request.originalRetentionStart().plusSeconds(86_400),
                        request.dueAt().plusSeconds(86_400),
                        request.authorizationReference());
        StepVerifier.create(
                        inTransaction(
                                ignored ->
                                        service.release(
                                                resetClock, List.of(), actor(), RELEASED_AT)))
                .expectErrorMessage("hold release attempted to reset retention time")
                .verify(TIMEOUT);
        assertThat(lifecycle(REQUEST_ID)).isEqualTo(suspended);
        assertThat(auditEventCount(AuditHoldService.RELEASED_EVENT)).isZero();

        StepVerifier.create(
                        inTransaction(
                                ignored ->
                                        service.release(request, List.of(), actor(), RELEASED_AT)))
                .assertNext(resumed -> assertOriginalClock(resumed, request))
                .expectComplete()
                .verify(TIMEOUT);

        LifecycleRow released = lifecycle(REQUEST_ID);
        assertThat(released.state()).isEqualTo("ELIGIBLE");
        assertThat(released.originalRetentionStart()).isEqualTo(request.originalRetentionStart());
        assertThat(released.originalDueAt()).isEqualTo(request.dueAt());
        assertThat(released.holdReferences()).isEqualTo("[]");
        assertThat(released.detectedAt()).isNull();
        assertThat(released.releasedAt()).isEqualTo(RELEASED_AT);
        assertThat(auditEventCount(AuditHoldService.RELEASED_EVENT)).isEqualTo(1);
        assertReleasePayload(request);
        assertCoveredPartitionRemainsAttached();
    }

    private AuditEmitter emitter() {
        AtomicInteger eventSequence = new AtomicInteger(32);
        RetentionResolver retention = new RetentionResolver(this::holdEventRetention);
        R2dbcAuditEmitter delegate =
                new R2dbcAuditEmitter(
                        retention,
                        (tenantId, period) -> AuditPostgreSqlFixture.SHARD_COUNT,
                        () ->
                                UUID.fromString(
                                        "019dca1d-c600-7000-8000-0000000000"
                                                + eventSequence.getAndIncrement()),
                        new CanonicalJsonCodec(),
                        new R2dbcAuditAppendRepository());
        return delegate;
    }

    private RetentionDecision holdEventRetention(
            Instant occurredAt,
            String eventType,
            String entityType,
            java.util.Set<RetentionClass> candidates) {
        assertThat(eventType)
                .isIn(AuditHoldService.SUPPRESSED_EVENT, AuditHoldService.RELEASED_EVENT);
        assertThat(entityType).isEqualTo("audit.epoch");
        assertThat(candidates).containsExactly(RetentionClass.RESULT_CORRECTION_EVIDENCE);
        return new RetentionDecision(
                "audit.hold.evidence",
                1,
                Instant.EPOCH,
                Optional.empty(),
                Map.of(RetentionClass.RESULT_CORRECTION_EVIDENCE, RetentionHorizon.indefinite()),
                RetentionClass.RESULT_CORRECTION_EVIDENCE);
    }

    private DispositionRequest heldRequest(UUID requestId) {
        EventSeed heldEvent = heldEvent();
        return new DispositionRequest(
                requestId,
                TENANT_ID,
                new EpochIdentity(
                        RetentionClass.valueOf(heldEvent.retentionClass()),
                        YearMonth.from(heldEvent.period())),
                heldEvent.policyKey(),
                heldEvent.policyVersion(),
                heldEvent.retentionStartedAt(),
                heldEvent.retentionUntil(),
                "privacy-hold-approval-031");
    }

    private EventSeed heldEvent() {
        UUID covered = fixture.legalHolds().getFirst().coveredEventId();
        return fixture.events().stream()
                .map(AuditPostgreSqlFixture.SeededEvent::event)
                .filter(event -> event.eventId().equals(covered))
                .findFirst()
                .orElseThrow();
    }

    private ActiveLegalHold activeHold() {
        return new ActiveLegalHold(
                fixture.legalHolds().getFirst().holdReference(), "legal-basis-7");
    }

    private static ActorContext actor() {
        return ActorContext.tenantSystem(
                SystemActor.RETENTION_ENGINE,
                TENANT_ID,
                CorrelationId.parse("01ARZ3NDEKTSV4RRFFQ69G5FAB"),
                SourceIp.parse("127.0.0.1"));
    }

    private <T> Mono<T> inTransaction(Function<TransactionalConnection, Mono<T>> work) {
        return Mono.usingWhen(
                Mono.from(connectionFactory.create()),
                connection -> {
                    TransactionalConnection handle = connection::createStatement;
                    return Mono.from(connection.beginTransaction())
                            .then(
                                    Mono.defer(() -> work.apply(handle))
                                            .contextWrite(
                                                    context ->
                                                            context.put(
                                                                    TransactionalConnection.class,
                                                                    handle)));
                },
                connection ->
                        Mono.from(connection.commitTransaction())
                                .then(Mono.from(connection.close())),
                (connection, failure) ->
                        Mono.from(connection.rollbackTransaction())
                                .onErrorResume(
                                        cleanupFailure -> {
                                            failure.addSuppressed(cleanupFailure);
                                            return Mono.empty();
                                        })
                                .then(Mono.from(connection.close())),
                connection ->
                        Mono.from(connection.rollbackTransaction())
                                .onErrorResume(ignored -> Mono.empty())
                                .then(Mono.from(connection.close())));
    }

    private void migrateDatabase() throws Exception {
        String migratorPassword = UUID.randomUUID().toString();
        try (Connection connection = ownerConnection();
                var statement = connection.createStatement();
                var input =
                        new ClassPathResource("db/provisioning/V1__create_migration_role.sql")
                                .getInputStream()) {
            statement.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            statement.execute("ALTER ROLE app_migrator PASSWORD '" + migratorPassword + "'");
        }
        MigrationApplication.run(
                new String[] {
                    "--migrate-only",
                    "--cbt.migration.jdbc-url=" + jdbcUrl,
                    "--cbt.migration.username=app_migrator",
                    "--cbt.migration.classification=EXPAND",
                    "--cbt.database.roles.app-migrator.password=" + migratorPassword
                });
    }

    private ConnectionFactory connectionFactory(String database) {
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

    private void provisionEvidenceEpoch(Connection connection) throws SQLException {
        try (var month = connection.prepareStatement("SELECT audit.provision_audit_month(?)");
                var heads =
                        connection.prepareStatement(
                                "SELECT audit.provision_audit_epoch_heads(?, ?, ?, ?, ?)")) {
            month.setDate(1, Date.valueOf(EVIDENCE_PERIOD.atDay(1)));
            month.execute();
            heads.setObject(1, AuditPostgreSqlFixture.TENANT_ID);
            heads.setString(2, RetentionClass.RESULT_CORRECTION_EVIDENCE.name());
            heads.setDate(3, Date.valueOf(EVIDENCE_PERIOD.atDay(1)));
            heads.setInt(4, AuditPostgreSqlFixture.SHARD_COUNT);
            heads.setShort(5, CanonicalJsonCodec.HASH_ALGORITHM_VERSION);
            heads.execute();
        }
    }

    private int lifecycleCount(UUID requestId) throws SQLException {
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT count(*) FROM audit.audit_disposition_lifecycle "
                                        + "WHERE request_id = ?")) {
            statement.setObject(1, requestId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private LifecycleRow lifecycle(UUID requestId) throws SQLException {
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement(
                                """
                                SELECT state, original_retention_start, original_due_at,
                                       hold_references::text, hold_detected_at, released_at
                                FROM audit.audit_disposition_lifecycle
                                WHERE request_id = ?
                                """)) {
            statement.setObject(1, requestId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                LifecycleRow row =
                        new LifecycleRow(
                                rows.getString(1),
                                rows.getTimestamp(2).toInstant(),
                                rows.getTimestamp(3).toInstant(),
                                rows.getString(4),
                                nullableInstant(rows.getTimestamp(5)),
                                nullableInstant(rows.getTimestamp(6)));
                assertThat(rows.next()).isFalse();
                return row;
            }
        }
    }

    private int auditEventCount(String eventType) throws SQLException {
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT count(*) FROM audit.audit_event "
                                        + "WHERE tenant_id = ? AND event_type = ?")) {
            statement.setObject(1, AuditPostgreSqlFixture.TENANT_ID);
            statement.setString(2, eventType);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private void assertSuppressionPayload(DispositionRequest request) throws SQLException {
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement(
                                """
                                SELECT payload ->> 'partition', payload ->> 'policy_key',
                                       (payload ->> 'policy_version')::bigint,
                                       payload ->> 'request_id', payload -> 'hold_references'
                                FROM audit.audit_event
                                WHERE tenant_id = ? AND event_type = ?
                                """)) {
            statement.setObject(1, AuditPostgreSqlFixture.TENANT_ID);
            statement.setString(2, AuditHoldService.SUPPRESSED_EVENT);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("RESULT_PUBLICATION_EVIDENCE:2026-02");
                assertThat(rows.getString(2)).isEqualTo(request.policyKey());
                assertThat(rows.getLong(3)).isEqualTo(request.policyVersion());
                assertThat(rows.getString(4)).isEqualTo(request.requestId().toString());
                assertThat(rows.getString(5))
                        .contains("\"hold_reference\": \"hold-audit-fixture-001\"")
                        .contains("\"legal_basis_reference\": \"legal-basis-7\"");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    private void assertReleasePayload(DispositionRequest request) throws SQLException {
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement(
                                """
                                SELECT payload ->> 'original_retention_start',
                                       payload ->> 'original_due_at',
                                       payload ->> 'released_at'
                                FROM audit.audit_event
                                WHERE tenant_id = ? AND event_type = ?
                                """)) {
            statement.setObject(1, AuditPostgreSqlFixture.TENANT_ID);
            statement.setString(2, AuditHoldService.RELEASED_EVENT);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(Instant.parse(rows.getString(1)))
                        .isEqualTo(request.originalRetentionStart());
                assertThat(Instant.parse(rows.getString(2))).isEqualTo(request.dueAt());
                assertThat(Instant.parse(rows.getString(3))).isEqualTo(RELEASED_AT);
                assertThat(rows.next()).isFalse();
            }
        }
    }

    private void assertCoveredPartitionRemainsAttached() throws SQLException {
        try (Connection connection = ownerConnection();
                var statement = connection.createStatement();
                var rows =
                        statement.executeQuery(
                                """
                                SELECT EXISTS (
                                    SELECT 1 FROM pg_catalog.pg_inherits
                                    WHERE inhrelid =
                                        'audit.audit_event_p_result_publication_y2026m02'::regclass
                                ), (
                                    SELECT count(*)
                                    FROM audit.audit_event_p_result_publication_y2026m02
                                    WHERE tenant_id =
                                        '00000000-0000-0000-0000-000000000071'::uuid
                                )
                                """)) {
            rows.next();
            assertThat(rows.getBoolean(1)).isTrue();
            assertThat(rows.getLong(2)).isEqualTo(2);
        }
    }

    private static void assertOriginalClock(
            ResumedDisposition resumed, DispositionRequest request) {
        assertThat(resumed.requestId()).isEqualTo(request.requestId());
        assertThat(resumed.originalRetentionStart()).isEqualTo(request.originalRetentionStart());
        assertThat(resumed.originalDueAt()).isEqualTo(request.dueAt());
        assertThat(resumed.originalDueAt()).isBefore(RELEASED_AT);
    }

    private static @Nullable Instant nullableInstant(@Nullable Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword());
    }

    private record LifecycleRow(
            String state,
            Instant originalRetentionStart,
            Instant originalDueAt,
            String holdReferences,
            @Nullable Instant detectedAt,
            @Nullable Instant releasedAt) {}
}
