package org.meldtech.platform.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meldtech.platform.PostgreSqlTestContainer;
import org.meldtech.platform.audit.application.AuditDispositionEligibility;
import org.meldtech.platform.audit.application.AuditDispositionEvidenceEmitter;
import org.meldtech.platform.audit.application.AuditDispositionExecutor;
import org.meldtech.platform.audit.application.AuditEpochCloser;
import org.meldtech.platform.audit.application.AuditEpochSealRepository;
import org.meldtech.platform.audit.application.AuditEpochSealer;
import org.meldtech.platform.audit.application.AuditEvidencePreservation;
import org.meldtech.platform.audit.application.AuditFullVerifier;
import org.meldtech.platform.audit.application.AuditIntegrityFailureHandler;
import org.meldtech.platform.audit.application.AuditVerificationFinding;
import org.meldtech.platform.audit.application.DispositionRequest;
import org.meldtech.platform.audit.domain.AuditHash;
import org.meldtech.platform.audit.domain.AuditHashing;
import org.meldtech.platform.audit.domain.AuditRootChainValidator;
import org.meldtech.platform.audit.domain.AuditRootHead;
import org.meldtech.platform.audit.domain.AuditSignature;
import org.meldtech.platform.audit.domain.AuditSigningMessage;
import org.meldtech.platform.audit.domain.CanonicalJsonCodec;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.audit.domain.EpochRootDerivation;
import org.meldtech.platform.audit.domain.EpochSealMaterial;
import org.meldtech.platform.audit.domain.RetentionResolver;
import org.meldtech.platform.audit.domain.SignedEpochSeal;
import org.meldtech.platform.audit.testing.AuditPostgreSqlFixture;
import org.meldtech.platform.audit.testing.AuditPostgreSqlFixture.EventSeed;
import org.meldtech.platform.migration.MigrationApplication;
import org.meldtech.platform.platform.infra.persistence.AuditTransactionTestSupport;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.InstantValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.IntegerValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;
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
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class AuditDispositionIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final CanonicalJsonCodec CODEC = new CanonicalJsonCodec();
    private static final TenantId TENANT_ID =
            TenantId.parse(AuditPostgreSqlFixture.TENANT_ID.toString());
    private static final EpochIdentity DISPOSED_EPOCH =
            new EpochIdentity(RetentionClass.GENERAL_AUDIT_EVENT, YearMonth.of(2026, 1));
    private static final EpochIdentity RETAINED_EPOCH =
            new EpochIdentity(RetentionClass.GENERAL_AUDIT_EVENT, YearMonth.of(2026, 3));
    private static final YearMonth EVIDENCE_PERIOD = YearMonth.of(2035, 1);
    private static final Instant SEAL_TIME = Instant.parse("2034-12-31T23:00:00Z");
    private static final Instant DISPOSITION_TIME = Instant.parse("2035-01-15T12:00:00Z");
    private static final UUID REQUEST_ID = UUID.fromString("019dca1d-c600-7000-8000-000000000019");

    private PostgreSQLContainer postgres;
    private String jdbcUrl;
    private ConnectionFactory connectionFactory;
    private AuditPostgreSqlFixture.Manifest fixture;

    @BeforeEach
    void migrateSeedAndSealFixture() throws Exception {
        postgres = PostgreSqlTestContainer.instance();
        postgres.start();
        String database = "audit_disposition_" + UUID.randomUUID().toString().replace("-", "");
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
            fixture = AuditPostgreSqlFixture.seed(connection, this::canonicalFixtureHash);
            provisionRootAndEvidenceEpoch(connection);
        }
        sealFixtureEpochs();
    }

    @Test
    void dispositionPreservesItsProofAndAllRetainedEvidence() throws SQLException {
        StepVerifier.create(
                        sealer(
                                        new FixedTimeSealRepository(
                                                new R2dbcAuditEpochSealRepository(
                                                        connectionFactory),
                                                SEAL_TIME))
                                .seal(TENANT_ID, RETAINED_EPOCH))
                .expectNext(AuditEpochSealer.SealResult.SEALED)
                .expectComplete()
                .verify(TIMEOUT);
        EventSeed target = targetEvent();
        DispositionRequest request = dispositionRequest();
        SealRow targetSeal = seal(DISPOSED_EPOCH);
        assertThat(targetSeal.rootSequence()).isEqualTo(4L);
        assertThat(parentEpochEventCount()).isEqualTo(2);

        AuditFullVerifier verifier = fullVerifier();
        var operations = dispositionOperations(ignored -> Mono.just(true));

        StepVerifier.create(new AuditDispositionExecutor(operations).execute(request))
                .assertNext(
                        result -> {
                            assertThat(result.requestId()).isEqualTo(REQUEST_ID);
                            assertThat(result.completedStep())
                                    .isEqualTo(
                                            AuditDispositionExecutor.Step
                                                    .RETAINED_EVIDENCE_VERIFIED);
                        })
                .expectComplete()
                .verify(TIMEOUT);

        assertDispositionEvidence(targetSeal, target);
        assertDispositionProgress();
        assertDetachedEpoch();
        assertDensePermanentRootChain();
        assertFullVerification(verifier);
    }

    @ParameterizedTest(name = "seal commits before detach: {0}")
    @ValueSource(booleans = {true, false})
    void dispositionPreservesAnInFlightSeal(boolean commitBeforeDetach) throws SQLException {
        Sinks.One<SignedEpochSeal> prepared = Sinks.one();
        Sinks.Empty<Void> allowCommit = Sinks.empty();
        Sinks.Empty<Void> committed = Sinks.empty();
        List<String> interleaving = new CopyOnWriteArrayList<>();
        List<String> retainedRows = retainedEpochRows();
        assertThat(retainedRows).hasSize(2);
        SealRow disposedSeal = seal(DISPOSED_EPOCH);
        var repository =
                new FixedTimeSealRepository(
                        new R2dbcAuditEpochSealRepository(connectionFactory),
                        SEAL_TIME,
                        pending -> {
                            interleaving.add("seal-prepared");
                            assertThat(prepared.tryEmitValue(pending))
                                    .isEqualTo(Sinks.EmitResult.OK);
                            return allowCommit.asMono();
                        });
        Mono<AuditEpochSealer.SealResult> sealing =
                sealer(repository)
                        .seal(TENANT_ID, RETAINED_EPOCH)
                        .doOnNext(
                                result -> {
                                    assertThat(result)
                                            .isEqualTo(AuditEpochSealer.SealResult.SEALED);
                                    interleaving.add("seal-committed");
                                    assertThat(committed.tryEmitEmpty())
                                            .isEqualTo(Sinks.EmitResult.OK);
                                });
        var operations =
                dispositionOperations(
                        ignored ->
                                Mono.fromCallable(
                                                () -> {
                                                    assertThat(parentEpochEventCount())
                                                            .isEqualTo(2);
                                                    assertDispositionEvidence(
                                                            disposedSeal, targetEvent());
                                                    interleaving.add("eligible-before-detach");
                                                    return true;
                                                })
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .flatMap(
                                                eligible -> {
                                                    if (!commitBeforeDetach) {
                                                        return Mono.just(eligible);
                                                    }
                                                    assertThat(allowCommit.tryEmitEmpty())
                                                            .isEqualTo(Sinks.EmitResult.OK);
                                                    return committed.asMono().thenReturn(eligible);
                                                }));
        Mono<AuditDispositionExecutor.DispositionResult> disposition =
                prepared.asMono()
                        .flatMap(
                                pending -> {
                                    assertThat(pending.evidence().material().epoch())
                                            .isEqualTo(RETAINED_EPOCH);
                                    assertThat(pending.evidence().material().rootSequence())
                                            .isEqualTo(12);
                                    return assertRootEvidence(11)
                                            .then(
                                                    new AuditDispositionExecutor(operations)
                                                            .execute(dispositionRequest()));
                                })
                        .flatMap(
                                result ->
                                        assertRootEvidence(commitBeforeDetach ? 12 : 11)
                                                .then(
                                                        Mono.fromCallable(
                                                                        () -> {
                                                                            assertDetachedEpoch();
                                                                            interleaving.add(
                                                                                    "disposed-and-verified");
                                                                            if (!commitBeforeDetach) {
                                                                                assertThat(
                                                                                                allowCommit
                                                                                                        .tryEmitEmpty())
                                                                                        .isEqualTo(
                                                                                                Sinks
                                                                                                        .EmitResult
                                                                                                        .OK);
                                                                            }
                                                                            return result;
                                                                        })
                                                                .subscribeOn(
                                                                        Schedulers
                                                                                .boundedElastic())));

        StepVerifier.create(Mono.zip(sealing, disposition))
                .assertNext(
                        results -> {
                            assertThat(results.getT1())
                                    .isEqualTo(AuditEpochSealer.SealResult.SEALED);
                            assertThat(results.getT2().completedStep())
                                    .isEqualTo(
                                            AuditDispositionExecutor.Step
                                                    .RETAINED_EVIDENCE_VERIFIED);
                        })
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(interleaving)
                .containsExactlyElementsOf(
                        commitBeforeDetach
                                ? List.of(
                                        "seal-prepared",
                                        "eligible-before-detach",
                                        "seal-committed",
                                        "disposed-and-verified")
                                : List.of(
                                        "seal-prepared",
                                        "eligible-before-detach",
                                        "disposed-and-verified",
                                        "seal-committed"));
        assertThat(retainedEpochRows()).isEqualTo(retainedRows);
        assertThat(seal(RETAINED_EPOCH).rootSequence()).isEqualTo(12);
        assertDispositionEvidence(disposedSeal, targetEvent());
        assertDispositionProgress();
        assertDensePermanentRootChain();
        assertFullVerification(fullVerifier());
    }

    @Test
    void verificationRetriesWhenASealCommitsAfterTheObservedHead() {
        var repository = new R2dbcAuditEpochSealRepository(connectionFactory);
        StepVerifier.create(
                        repository
                                .readRootHead(TENANT_ID)
                                .flatMap(
                                        observedHead -> {
                                            assertThat(observedHead.sequence()).isEqualTo(11);
                                            AuditEpochSealRepository delayedHead =
                                                    mock(AuditEpochSealRepository.class);
                                            when(delayedHead.readRootHead(TENANT_ID))
                                                    .thenReturn(Mono.just(observedHead))
                                                    .thenReturn(repository.readRootHead(TENANT_ID));
                                            var evidence =
                                                    new R2dbcAuditFullVerificationEvidence(
                                                            connectionFactory,
                                                            TENANT_ID,
                                                            CODEC,
                                                            new ObjectMapper(),
                                                            delayedHead);
                                            // Deliver the observed head only after another writer
                                            // has committed.
                                            return sealer(
                                                            new FixedTimeSealRepository(
                                                                    repository, SEAL_TIME))
                                                    .seal(TENANT_ID, RETAINED_EPOCH)
                                                    .then(evidence.rootEvidence());
                                        }))
                .assertNext(
                        roots -> {
                            assertThat(roots.seals()).hasSize(12);
                            assertThat(roots.currentHead().sequence()).isEqualTo(12);
                            rootValidator()
                                    .validate(roots.tenantId(), roots.seals(), roots.currentHead());
                        })
                .expectComplete()
                .verify(TIMEOUT);
        StepVerifier.create(assertRootEvidence(12)).expectComplete().verify(TIMEOUT);
        assertFullVerificationBeforeDisposition();
    }

    @Test
    void continuousRootMovementStopsAfterBoundedRetriesWithoutReportingCorruption() {
        AuditEpochSealRepository movingHeads = mock(AuditEpochSealRepository.class);
        AtomicLong sequence = new AtomicLong(11);
        var repository = new R2dbcAuditEpochSealRepository(connectionFactory);
        StepVerifier.create(
                        repository
                                .readRootHead(TENANT_ID)
                                .flatMap(
                                        initial -> {
                                            when(movingHeads.readRootHead(TENANT_ID))
                                                    .thenAnswer(
                                                            ignored ->
                                                                    Mono.fromSupplier(
                                                                            () ->
                                                                                    new AuditRootHead(
                                                                                            sequence
                                                                                                    .getAndIncrement(),
                                                                                            initial
                                                                                                    .hash())));
                                            return new R2dbcAuditFullVerificationEvidence(
                                                            connectionFactory,
                                                            TENANT_ID,
                                                            CODEC,
                                                            new ObjectMapper(),
                                                            movingHeads)
                                                    .rootEvidence();
                                        }))
                .expectErrorSatisfies(
                        failure ->
                                assertThat(failure)
                                        .isExactlyInstanceOf(IllegalStateException.class)
                                        .hasMessage("audit root advancing; retry verification"))
                .verify(TIMEOUT);
        verify(movingHeads, times(8)).readRootHead(TENANT_ID);
        StepVerifier.create(assertRootEvidence(11)).expectComplete().verify(TIMEOUT);
    }

    private void assertFullVerificationBeforeDisposition() {
        StepVerifier.create(fullVerifier().verify(AuditFullVerifier.Trigger.QUARTERLY))
                .assertNext(
                        summary -> {
                            assertThat(summary.retainedChains()).isEqualTo(24);
                            assertThat(summary.tenants()).isEqualTo(1);
                        })
                .expectComplete()
                .verify(TIMEOUT);
    }

    private Mono<Void> assertRootEvidence(int sealCount) {
        return new R2dbcAuditFullVerificationEvidence(connectionFactory, TENANT_ID, CODEC)
                .rootEvidence()
                .doOnNext(
                        roots -> {
                            assertThat(roots.seals()).hasSize(sealCount);
                            rootValidator()
                                    .validate(roots.tenantId(), roots.seals(), roots.currentHead());
                        })
                .then();
    }

    private DispositionRequest dispositionRequest() {
        EventSeed target = targetEvent();
        return new DispositionRequest(
                REQUEST_ID,
                TENANT_ID,
                DISPOSED_EPOCH,
                target.policyKey(),
                target.policyVersion(),
                target.retentionStartedAt(),
                target.retentionUntil(),
                "privacy-approval-032");
    }

    private R2dbcAuditDispositionOperations dispositionOperations(
            AuditDispositionEligibility eligibility) {
        R2dbcAuditFullVerificationEvidence evidence =
                new R2dbcAuditFullVerificationEvidence(connectionFactory, TENANT_ID, CODEC);
        RetentionResolver retention = new RetentionResolver(this::dispositionRetention);
        R2dbcAuditEmitter emitter =
                new R2dbcAuditEmitter(
                        retention,
                        (tenantId, period) -> AuditPostgreSqlFixture.SHARD_COUNT,
                        () -> UUID.fromString("019dca1d-c600-7000-8000-000000000020"),
                        CODEC,
                        new R2dbcAuditAppendRepository());
        ActorContext actor =
                ActorContext.tenantSystem(
                        SystemActor.RETENTION_ENGINE,
                        TENANT_ID,
                        CorrelationId.parse("01ARZ3NDEKTSV4RRFFQ69G5FAA"),
                        SourceIp.parse("127.0.0.1"));
        return new R2dbcAuditDispositionOperations(
                connectionFactory,
                evidence,
                AuditDispositionIntegrationTest::verifySignature,
                rootValidator(),
                CODEC,
                fullVerifier(),
                new AuditDispositionEvidenceEmitter(emitter),
                AuditTransactionTestSupport.collaboration(connectionFactory),
                eligibility,
                actor,
                Clock.fixed(DISPOSITION_TIME, ZoneOffset.UTC));
    }

    private static void assertFullVerification(AuditFullVerifier verifier) {
        StepVerifier.create(verifier.verify(AuditFullVerifier.Trigger.QUARTERLY))
                .assertNext(
                        summary -> {
                            assertThat(summary.retainedChains()).isEqualTo(23);
                            assertThat(summary.tenants()).isEqualTo(1);
                        })
                .expectComplete()
                .verify(TIMEOUT);
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

    private void provisionRootAndEvidenceEpoch(Connection connection) throws SQLException {
        try (var month = connection.prepareStatement("SELECT audit.provision_audit_month(?)");
                var root =
                        connection.prepareStatement("SELECT audit.provision_audit_root_head(?)");
                var heads =
                        connection.prepareStatement(
                                "SELECT audit.provision_audit_epoch_heads(?, ?, ?, ?, ?)")) {
            month.setDate(1, Date.valueOf(EVIDENCE_PERIOD.atDay(1)));
            month.execute();
            root.setObject(1, AuditPostgreSqlFixture.TENANT_ID);
            root.execute();
            heads.setObject(1, AuditPostgreSqlFixture.TENANT_ID);
            heads.setString(2, RetentionClass.RESULT_CORRECTION_EVIDENCE.name());
            heads.setDate(3, Date.valueOf(EVIDENCE_PERIOD.atDay(1)));
            heads.setInt(4, AuditPostgreSqlFixture.SHARD_COUNT);
            heads.setShort(5, CanonicalJsonCodec.HASH_ALGORITHM_VERSION);
            heads.execute();
        }
    }

    private void sealFixtureEpochs() {
        AuditEpochSealRepository repository =
                new FixedTimeSealRepository(
                        new R2dbcAuditEpochSealRepository(connectionFactory), SEAL_TIME);
        AuditEpochSealer sealer = sealer(repository);
        List<EpochIdentity> epochs =
                AuditPostgreSqlFixture.PERIODS.stream()
                        .flatMap(
                                period ->
                                        AuditPostgreSqlFixture.RETENTION_CLASSES.stream()
                                                .map(
                                                        retentionClass ->
                                                                new EpochIdentity(
                                                                        RetentionClass.valueOf(
                                                                                retentionClass),
                                                                        YearMonth.from(period))))
                        .filter(epoch -> !epoch.equals(RETAINED_EPOCH))
                        .toList();

        StepVerifier.create(new AuditEpochCloser(sealer).close(TENANT_ID, epochs.reversed()))
                .assertNext(
                        closed -> {
                            assertThat(closed).hasSize(11);
                            assertThat(closed)
                                    .allMatch(
                                            epoch ->
                                                    epoch.result()
                                                            == AuditEpochSealer.SealResult.SEALED);
                        })
                .expectComplete()
                .verify(TIMEOUT);
    }

    private static AuditEpochSealer sealer(AuditEpochSealRepository repository) {
        return new AuditEpochSealer(
                repository,
                message ->
                        Mono.fromCallable(
                                () ->
                                        new AuditSignature(
                                                "integration-key-v1",
                                                "TEST_SHA256",
                                                MessageDigest.getInstance("SHA-256")
                                                        .digest(message.bytes()),
                                                "seal-request-" + message.evidenceId(),
                                                SEAL_TIME)),
                new EpochRootDerivation(CODEC),
                CODEC);
    }

    private static Mono<Boolean> verifySignature(
            AuditSigningMessage message, AuditSignature signature) {
        return Mono.fromCallable(
                () ->
                        Arrays.equals(
                                MessageDigest.getInstance("SHA-256").digest(message.bytes()),
                                signature.signature()));
    }

    private byte[] canonicalFixtureHash(byte[] previousHash, EventSeed event) {
        ObjectValue payload =
                new ObjectValue(
                        Map.of(
                                "fixture_event_id", new StringValue(event.eventId().toString()),
                                "policy_version", new IntegerValue(event.policyVersion())));
        ObjectValue envelope =
                new ObjectValue(
                        Map.ofEntries(
                                Map.entry("event_type", new StringValue("audit.FIXTURE_EVENT.v1")),
                                Map.entry("entity_type", new StringValue("fixture")),
                                Map.entry("entity_id", new StringValue(event.entityId())),
                                Map.entry("actor_type", new StringValue("SYSTEM")),
                                Map.entry("actor_id", new StringValue("audit-fixture")),
                                Map.entry("system_actor_name", new StringValue("audit-fixture")),
                                Map.entry("tenant_id", new StringValue(TENANT_ID.toString())),
                                Map.entry("occurred_at", new InstantValue(event.occurredAt())),
                                Map.entry(
                                        "correlation_id",
                                        new StringValue("01ARZ3NDEKTSV4RRFFQ69G5FAV")),
                                Map.entry(
                                        "retention_class", new StringValue(event.retentionClass())),
                                Map.entry(
                                        "retention_policy_key", new StringValue(event.policyKey())),
                                Map.entry(
                                        "retention_policy_version",
                                        new IntegerValue(event.policyVersion())),
                                Map.entry(
                                        "retention_until",
                                        new InstantValue(event.retentionUntil())),
                                Map.entry(
                                        "period",
                                        new StringValue(YearMonth.from(event.period()).toString())),
                                Map.entry("shard_id", new IntegerValue(event.shardId())),
                                Map.entry("seq", new IntegerValue(1)),
                                Map.entry("hash_algo_version", new IntegerValue(1)),
                                Map.entry("payload", payload)));
        return AuditHashing.recordHash(
                        new AuditHash((short) 1, previousHash), CODEC.encode(envelope))
                .bytes();
    }

    private RetentionDecision dispositionRetention(
            Instant occurredAt,
            String eventType,
            String entityType,
            java.util.Set<RetentionClass> candidates) {
        assertThat(eventType).isEqualTo(AuditDispositionEvidenceEmitter.EVENT_TYPE);
        assertThat(entityType).isEqualTo("audit.epoch");
        assertThat(candidates).containsExactly(RetentionClass.RESULT_CORRECTION_EVIDENCE);
        return new RetentionDecision(
                "audit.disposition.evidence",
                1,
                Instant.EPOCH,
                Optional.empty(),
                Map.of(RetentionClass.RESULT_CORRECTION_EVIDENCE, RetentionHorizon.indefinite()),
                RetentionClass.RESULT_CORRECTION_EVIDENCE);
    }

    private AuditFullVerifier fullVerifier() {
        R2dbcAuditFullVerificationEvidence evidence =
                new R2dbcAuditFullVerificationEvidence(connectionFactory, TENANT_ID, CODEC);
        return new AuditFullVerifier(
                evidence,
                AuditDispositionIntegrationTest::verifySignature,
                rootValidator(),
                CODEC,
                (tenantId, category, identities) ->
                        Mono.error(new AssertionError("verification unexpectedly failed")),
                new AuditIntegrityFailureHandler(new UnexpectedPreservation()));
    }

    private static AuditRootChainValidator rootValidator() {
        return new AuditRootChainValidator(new EpochRootDerivation(CODEC));
    }

    private EventSeed targetEvent() {
        return fixture.events().stream()
                .map(AuditPostgreSqlFixture.SeededEvent::event)
                .filter(
                        event ->
                                event.retentionClass()
                                        .equals(DISPOSED_EPOCH.retentionClass().name()))
                .filter(event -> YearMonth.from(event.period()).equals(DISPOSED_EPOCH.period()))
                .findFirst()
                .orElseThrow();
    }

    private SealRow seal(EpochIdentity epoch) throws SQLException {
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement(
                                """
                                SELECT root_seq, encode(epoch_root, 'hex')
                                FROM audit.audit_chain_seal
                                WHERE tenant_id = ? AND retention_class = ? AND period = ?
                                """)) {
            statement.setObject(1, AuditPostgreSqlFixture.TENANT_ID);
            statement.setString(2, epoch.retentionClass().name());
            statement.setDate(3, Date.valueOf(epoch.period().atDay(1)));
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                SealRow seal = new SealRow(rows.getLong(1), rows.getString(2));
                assertThat(rows.next()).isFalse();
                return seal;
            }
        }
    }

    private long parentEpochEventCount() throws SQLException {
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement(
                                """
                                SELECT count(*) FROM audit.audit_event
                                WHERE tenant_id = ? AND retention_class = ? AND period = ?
                                """)) {
            statement.setObject(1, AuditPostgreSqlFixture.TENANT_ID);
            statement.setString(2, DISPOSED_EPOCH.retentionClass().name());
            statement.setDate(3, Date.valueOf(DISPOSED_EPOCH.period().atDay(1)));
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private List<String> retainedEpochRows() throws SQLException {
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement(
                                """
                        SELECT row_to_json(event)::text FROM audit.audit_event event
                        WHERE tenant_id = ? AND retention_class = ? AND period = ?
                        ORDER BY shard_id, seq
                        """)) {
            statement.setObject(1, AuditPostgreSqlFixture.TENANT_ID);
            statement.setString(2, RETAINED_EPOCH.retentionClass().name());
            statement.setDate(3, Date.valueOf(RETAINED_EPOCH.period().atDay(1)));
            try (var rows = statement.executeQuery()) {
                List<String> snapshot = new java.util.ArrayList<>();
                while (rows.next()) {
                    snapshot.add(rows.getString(1));
                }
                return List.copyOf(snapshot);
            }
        }
    }

    private void assertDispositionEvidence(SealRow targetSeal, EventSeed target)
            throws SQLException {
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement(
                                """
                                SELECT payload ->> 'disposed_retention_class',
                                       payload ->> 'disposed_period',
                                       (payload ->> 'root_seq')::bigint,
                                       payload ->> 'root_hash',
                                       payload ->> 'policy_key',
                                       (payload ->> 'policy_version')::bigint,
                                       payload -> 'sequence_ranges'
                                FROM audit.audit_event
                                WHERE tenant_id = ? AND event_type = ?
                                  AND payload ->> 'request_id' = ?
                                """)) {
            statement.setObject(1, AuditPostgreSqlFixture.TENANT_ID);
            statement.setString(2, AuditDispositionEvidenceEmitter.EVENT_TYPE);
            statement.setString(3, REQUEST_ID.toString());
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(DISPOSED_EPOCH.retentionClass().name());
                assertThat(rows.getString(2)).isEqualTo(DISPOSED_EPOCH.period().toString());
                assertThat(rows.getLong(3)).isEqualTo(targetSeal.rootSequence());
                assertThat(rows.getString(4)).isEqualTo(targetSeal.rootHash());
                assertThat(rows.getString(5)).isEqualTo(target.policyKey());
                assertThat(rows.getLong(6)).isEqualTo(target.policyVersion());
                assertThat(rows.getString(7))
                        .contains("\"shard_id\": 0", "\"seq_start\": 1", "\"seq_end\": 1")
                        .contains("\"shard_id\": 1");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    private void assertDispositionProgress() throws SQLException {
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement(
                                """
                                SELECT state, last_completed_step
                                FROM audit.audit_disposition_lifecycle
                                WHERE request_id = ?
                                """)) {
            statement.setObject(1, REQUEST_ID);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("COMPLETED");
                assertThat(rows.getString(2)).isEqualTo("RETAINED_EVIDENCE_VERIFIED");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    private void assertDetachedEpoch() throws SQLException {
        assertThat(parentEpochEventCount()).isZero();
        try (Connection connection = ownerConnection();
                var statement = connection.createStatement();
                var rows =
                        statement.executeQuery(
                                """
                                SELECT count(*) FROM audit.audit_event_p_general_y2026m01
                                WHERE tenant_id = '00000000-0000-0000-0000-000000000071'
                                """)) {
            rows.next();
            assertThat(rows.getLong(1)).isEqualTo(2);
        }
        try (Connection connection = ownerConnection();
                var statement = connection.createStatement();
                var rows =
                        statement.executeQuery(
                                """
                                SELECT count(*)
                                FROM pg_catalog.pg_inherits
                                WHERE inhrelid = 'audit.audit_event_p_general_y2026m01'::regclass
                                """)) {
            rows.next();
            assertThat(rows.getLong(1)).isZero();
        }
    }

    private void assertDensePermanentRootChain() throws SQLException {
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement(
                                """
                                SELECT count(*), min(root_seq), max(root_seq), count(DISTINCT root_seq)
                                FROM audit.audit_chain_seal WHERE tenant_id = ?
                                """)) {
            statement.setObject(1, AuditPostgreSqlFixture.TENANT_ID);
            try (var rows = statement.executeQuery()) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(12);
                assertThat(rows.getLong(2)).isEqualTo(1);
                assertThat(rows.getLong(3)).isEqualTo(12);
                assertThat(rows.getLong(4)).isEqualTo(12);
            }
        }
        assertThat(seal(DISPOSED_EPOCH)).isNotNull();
    }

    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword());
    }

    private record SealRow(long rootSequence, String rootHash) {}

    private static final class FixedTimeSealRepository implements AuditEpochSealRepository {

        private final AuditEpochSealRepository delegate;
        private final Instant signingTime;
        private final Function<SignedEpochSeal, Mono<Void>> beforeCommit;

        private FixedTimeSealRepository(AuditEpochSealRepository delegate, Instant signingTime) {
            this(delegate, signingTime, ignored -> Mono.empty());
        }

        private FixedTimeSealRepository(
                AuditEpochSealRepository delegate,
                Instant signingTime,
                Function<SignedEpochSeal, Mono<Void>> beforeCommit) {
            this.delegate = delegate;
            this.signingTime = signingTime;
            this.beforeCommit = beforeCommit;
        }

        @Override
        public Mono<AuditRootHead> readRootHead(TenantId tenantId) {
            return delegate.readRootHead(tenantId);
        }

        @Override
        public Mono<EpochSealMaterial> loadEpochMaterial(
                TenantId tenantId, EpochIdentity epoch, AuditRootHead observedRootHead) {
            return delegate.loadEpochMaterial(tenantId, epoch, observedRootHead);
        }

        @Override
        public Mono<Instant> trustedSigningTime() {
            return Mono.just(signingTime);
        }

        @Override
        public Mono<Boolean> insertSealAndCompareAndSwap(
                SignedEpochSeal seal, AuditRootHead observedRootHead) {
            return Mono.defer(() -> beforeCommit.apply(seal))
                    .then(
                            Mono.defer(
                                    () ->
                                            delegate.insertSealAndCompareAndSwap(
                                                    seal, observedRootHead)));
        }
    }

    private static final class UnexpectedPreservation implements AuditEvidencePreservation {

        @Override
        public Mono<Void> preserve(AuditVerificationFinding finding) {
            return unexpected();
        }

        @Override
        public Mono<Void> recordHighSeverityMetric(AuditVerificationFinding finding) {
            return unexpected();
        }

        @Override
        public Mono<Void> raiseP1Alert(AuditVerificationFinding finding) {
            return unexpected();
        }

        @Override
        public Mono<Void> haltSealingAndDisposition(TenantId tenantId) {
            return unexpected();
        }

        private static Mono<Void> unexpected() {
            return Mono.error(new AssertionError("verification evidence was not preserved"));
        }
    }
}
