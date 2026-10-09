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
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.meldtech.platform.PostgreSqlTestContainer;
import org.meldtech.platform.audit.application.AuditEpochCloser;
import org.meldtech.platform.audit.application.AuditEpochSealRepository;
import org.meldtech.platform.audit.application.AuditEpochSealer;
import org.meldtech.platform.audit.application.AuditEvidenceSigner;
import org.meldtech.platform.audit.domain.AuditRootHead;
import org.meldtech.platform.audit.domain.AuditSignature;
import org.meldtech.platform.audit.domain.CanonicalJsonCodec;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.audit.domain.EpochRootDerivation;
import org.meldtech.platform.audit.domain.EpochSealMaterial;
import org.meldtech.platform.audit.domain.SignedEpochSeal;
import org.meldtech.platform.migration.MigrationApplication;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditEpochSealConcurrencyIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Instant SIGNED_AT = Instant.parse("2026-10-09T12:00:00Z");
    private static final YearMonth PERIOD = YearMonth.of(2026, 9);

    private PostgreSQLContainer postgres;
    private String jdbcUrl;
    private ConnectionFactory connectionFactory;

    @BeforeAll
    void migrate() throws Exception {
        postgres = PostgreSqlTestContainer.instance();
        postgres.start();
        String database = "audit_seal_" + UUID.randomUUID().toString().replace("-", "");
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
        connectionFactory =
                ConnectionFactories.get(
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
    void concurrentSealersRecordOneCasLossAndAppendADenseRootChain() throws SQLException {
        TenantId tenantId = provisionTenant();
        var repository =
                new ObservedSealRepository(
                        new R2dbcAuditEpochSealRepository(connectionFactory), SIGNED_AT);
        AtomicInteger retryCount = new AtomicInteger();
        AtomicInteger signatureCount = new AtomicInteger();
        CyclicBarrier firstAttemptBarrier = new CyclicBarrier(2);
        AuditEvidenceSigner signer =
                message ->
                        Mono.fromCallable(
                                        () -> {
                                            int attempt = signatureCount.incrementAndGet();
                                            if (attempt <= 2) {
                                                firstAttemptBarrier.await(10, TimeUnit.SECONDS);
                                            }
                                            return new AuditSignature(
                                                    "integration-key-v1",
                                                    "TEST_SHA256",
                                                    new byte[] {(byte) attempt},
                                                    "sign-request-" + attempt,
                                                    SIGNED_AT);
                                        })
                                .subscribeOn(Schedulers.boundedElastic());
        CanonicalJsonCodec codec = new CanonicalJsonCodec();
        AuditEpochSealer first = sealer(repository, signer, codec, retryCount);
        AuditEpochSealer second = sealer(repository, signer, codec, retryCount);

        StepVerifier.create(
                        Mono.zip(
                                first.seal(
                                        tenantId,
                                        new EpochIdentity(
                                                RetentionClass.RESULT_CORRECTION_EVIDENCE, PERIOD)),
                                second.seal(
                                        tenantId,
                                        new EpochIdentity(
                                                RetentionClass.RESULT_PUBLICATION_EVIDENCE,
                                                PERIOD))))
                .assertNext(
                        results -> {
                            assertThat(results.getT1())
                                    .isEqualTo(AuditEpochSealer.SealResult.SEALED);
                            assertThat(results.getT2())
                                    .isEqualTo(AuditEpochSealer.SealResult.SEALED);
                        })
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(signatureCount).hasValue(3);
        assertThat(retryCount).hasValue(1);
        assertThat(repository.attempts())
                .extracting(SealAttempt::observedSequence)
                .containsExactlyInAnyOrder(0L, 0L, 1L);
        assertThat(repository.attempts())
                .filteredOn(attempt -> !attempt.committed())
                .extracting(SealAttempt::observedSequence)
                .containsExactly(0L);
        assertThat(repository.attempts())
                .filteredOn(SealAttempt::committed)
                .extracting(SealAttempt::observedSequence)
                .containsExactlyInAnyOrder(0L, 1L);
        assertLinearRootChain(tenantId);
    }

    @Test
    void restartAfterTerminationBetweenSigningAndCasResealsWithoutGapOrDuplicate()
            throws SQLException {
        TenantId tenantId = provisionTenant();
        EpochIdentity epoch = new EpochIdentity(RetentionClass.RESULT_CORRECTION_EVIDENCE, PERIOD);
        AtomicInteger signatureCount = new AtomicInteger();
        AtomicInteger retryCount = new AtomicInteger();
        CanonicalJsonCodec codec = new CanonicalJsonCodec();
        var killedRepository =
                new KillBeforeCommitRepository(
                        new R2dbcAuditEpochSealRepository(connectionFactory), SIGNED_AT);

        StepVerifier.create(
                        sealer(killedRepository, signer(signatureCount), codec, retryCount)
                                .seal(tenantId, epoch))
                .expectErrorMessage("simulated termination after KMS signature")
                .verify(TIMEOUT);

        assertThat(signatureCount).hasValue(1);
        assertThat(killedRepository.commitAttempts()).hasValue(1);
        assertThat(retryCount).hasValue(0);
        assertUnsealed(tenantId);

        var restartedRepository =
                new ObservedSealRepository(
                        new R2dbcAuditEpochSealRepository(connectionFactory), SIGNED_AT);
        StepVerifier.create(
                        sealer(restartedRepository, signer(signatureCount), codec, retryCount)
                                .seal(tenantId, epoch))
                .expectNext(AuditEpochSealer.SealResult.SEALED)
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(signatureCount).hasValue(2);
        assertThat(retryCount).hasValue(0);
        assertThat(restartedRepository.attempts()).containsExactly(new SealAttempt(0L, true));
        assertSingleSealAfterRestart(tenantId);
    }

    @Test
    void canonicalMultiEpochCloseReproducesTheSameRootSequence() throws SQLException {
        List<EpochIdentity> canonicalEpochs =
                List.of(
                        epoch(2026, 8, RetentionClass.GENERAL_AUDIT_EVENT),
                        epoch(2026, 9, RetentionClass.RESULT_CORRECTION_EVIDENCE),
                        epoch(2026, 9, RetentionClass.RESULT_PUBLICATION_EVIDENCE),
                        epoch(2026, 9, RetentionClass.PIN_SECURITY_EVENT),
                        epoch(2026, 9, RetentionClass.GENERAL_AUDIT_EVENT),
                        epoch(2026, 10, RetentionClass.RESULT_CORRECTION_EVIDENCE));
        List<EpochIdentity> firstInput =
                List.of(
                        canonicalEpochs.get(4),
                        canonicalEpochs.get(1),
                        canonicalEpochs.get(5),
                        canonicalEpochs.get(0),
                        canonicalEpochs.get(3),
                        canonicalEpochs.get(2));
        List<EpochIdentity> secondInput = canonicalEpochs.reversed();
        TenantId firstTenant = provisionTenant(canonicalEpochs);
        TenantId secondTenant = provisionTenant(canonicalEpochs);
        AtomicInteger firstRetries = new AtomicInteger();
        AtomicInteger secondRetries = new AtomicInteger();

        assertCloseOrder(firstTenant, firstInput, canonicalEpochs, firstRetries);
        assertCloseOrder(secondTenant, secondInput, canonicalEpochs, secondRetries);

        List<EpochSequence> firstRun = epochSequences(firstTenant);
        List<EpochSequence> secondRun = epochSequences(secondTenant);
        assertThat(firstRun).isEqualTo(secondRun);
        assertThat(firstRun)
                .extracting(EpochSequence::rootSequence)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(firstRun)
                .extracting(EpochSequence::epoch)
                .containsExactlyElementsOf(canonicalEpochs);
        assertThat(firstRetries).hasValue(0);
        assertThat(secondRetries).hasValue(0);
    }

    private static AuditEpochSealer sealer(
            AuditEpochSealRepository repository,
            AuditEvidenceSigner signer,
            CanonicalJsonCodec codec,
            AtomicInteger retryCount) {
        return new AuditEpochSealer(
                repository,
                signer,
                new EpochRootDerivation(codec),
                codec,
                retryCount::incrementAndGet,
                2);
    }

    private static AuditEvidenceSigner signer(AtomicInteger signatureCount) {
        return message -> {
            int signature = signatureCount.incrementAndGet();
            return Mono.just(
                    new AuditSignature(
                            "integration-key-v1",
                            "TEST_SHA256",
                            new byte[] {(byte) signature},
                            "sign-request-" + signature,
                            SIGNED_AT));
        };
    }

    private void assertCloseOrder(
            TenantId tenantId,
            List<EpochIdentity> input,
            List<EpochIdentity> expected,
            AtomicInteger retryCount) {
        CanonicalJsonCodec codec = new CanonicalJsonCodec();
        var repository =
                new ObservedSealRepository(
                        new R2dbcAuditEpochSealRepository(connectionFactory), SIGNED_AT);
        AuditEpochCloser closer =
                new AuditEpochCloser(
                        sealer(repository, signer(new AtomicInteger()), codec, retryCount));

        StepVerifier.create(closer.close(tenantId, input))
                .assertNext(
                        closed ->
                                assertThat(closed)
                                        .extracting(AuditEpochCloser.ClosedEpoch::epoch)
                                        .containsExactlyElementsOf(expected))
                .expectComplete()
                .verify(TIMEOUT);
    }

    private static EpochIdentity epoch(int year, int month, RetentionClass retentionClass) {
        return new EpochIdentity(retentionClass, YearMonth.of(year, month));
    }

    private TenantId provisionTenant() throws SQLException {
        return provisionTenant(
                List.of(
                        new EpochIdentity(RetentionClass.RESULT_CORRECTION_EVIDENCE, PERIOD),
                        new EpochIdentity(RetentionClass.RESULT_PUBLICATION_EVIDENCE, PERIOD)));
    }

    private TenantId provisionTenant(List<EpochIdentity> epochs) throws SQLException {
        TenantId tenantId = TenantId.parse(UUID.randomUUID().toString());
        try (Connection connection = ownerConnection();
                var month = connection.prepareStatement("SELECT audit.provision_audit_month(?)");
                var root =
                        connection.prepareStatement("SELECT audit.provision_audit_root_head(?)");
                var heads =
                        connection.prepareStatement(
                                "SELECT audit.provision_audit_epoch_heads(?, ?, ?, ?, ?)")) {
            for (YearMonth period :
                    epochs.stream().map(EpochIdentity::period).distinct().toList()) {
                month.setDate(1, Date.valueOf(period.atDay(1)));
                month.execute();
            }
            root.setObject(1, UUID.fromString(tenantId.toString()));
            root.execute();
            for (EpochIdentity epoch : epochs) {
                heads.setObject(1, UUID.fromString(tenantId.toString()));
                heads.setString(2, epoch.retentionClass().name());
                heads.setDate(3, Date.valueOf(epoch.period().atDay(1)));
                heads.setInt(4, 2);
                heads.setShort(5, (short) 1);
                heads.execute();
            }
        }
        return tenantId;
    }

    private List<EpochSequence> epochSequences(TenantId tenantId) throws SQLException {
        List<EpochSequence> sequences = new ArrayList<>();
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement(
                                """
                                SELECT retention_class, period, root_seq
                                FROM audit.audit_chain_seal
                                WHERE tenant_id = ?
                                ORDER BY root_seq
                                """)) {
            statement.setObject(1, UUID.fromString(tenantId.toString()));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    sequences.add(
                            new EpochSequence(
                                    new EpochIdentity(
                                            RetentionClass.valueOf(rows.getString(1)),
                                            YearMonth.from(rows.getDate(2).toLocalDate())),
                                    rows.getLong(3)));
                }
            }
        }
        return List.copyOf(sequences);
    }

    private void assertLinearRootChain(TenantId tenantId) throws SQLException {
        List<RootRow> seals = seals(tenantId);

        assertThat(seals).extracting(RootRow::sequence).containsExactly(1L, 2L);
        assertThat(seals.getFirst().previousHash()).isEqualTo("00".repeat(32));
        assertThat(seals.get(1).previousHash()).isEqualTo(seals.getFirst().epochRoot());
        assertThat(rootHead(tenantId))
                .isEqualTo(new RootHeadRow(2L, seals.get(1).epochRoot(), true));
    }

    private void assertUnsealed(TenantId tenantId) throws SQLException {
        assertThat(seals(tenantId)).isEmpty();
        assertThat(rootHead(tenantId)).isEqualTo(new RootHeadRow(0L, "00".repeat(32), false));
    }

    private void assertSingleSealAfterRestart(TenantId tenantId) throws SQLException {
        List<RootRow> seals = seals(tenantId);
        assertThat(seals).hasSize(1);
        RootRow seal = seals.getFirst();
        assertThat(seal.sequence()).isEqualTo(1L);
        assertThat(seal.previousHash()).isEqualTo("00".repeat(32));
        assertThat(seal.signatureRequestId()).isEqualTo("sign-request-2");
        assertThat(rootHead(tenantId)).isEqualTo(new RootHeadRow(1L, seal.epochRoot(), true));
    }

    private List<RootRow> seals(TenantId tenantId) throws SQLException {
        List<RootRow> seals = new ArrayList<>();
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement(
                                """
                                SELECT root_seq,
                                       encode(previous_root_hash, 'hex'),
                                       encode(epoch_root, 'hex'),
                                       signature_request_id
                                FROM audit.audit_chain_seal
                                WHERE tenant_id = ?
                                ORDER BY root_seq
                                """)) {
            statement.setObject(1, UUID.fromString(tenantId.toString()));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    seals.add(
                            new RootRow(
                                    rows.getLong(1),
                                    rows.getString(2),
                                    rows.getString(3),
                                    rows.getString(4)));
                }
            }
        }
        return List.copyOf(seals);
    }

    private RootHeadRow rootHead(TenantId tenantId) throws SQLException {
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement(
                                """
                                SELECT root_seq,
                                       encode(root_head_hash, 'hex'),
                                       sealed_at IS NOT NULL
                                FROM audit.audit_chain_root_head
                                WHERE tenant_id = ?
                                """)) {
            statement.setObject(1, UUID.fromString(tenantId.toString()));
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                RootHeadRow rootHead =
                        new RootHeadRow(rows.getLong(1), rows.getString(2), rows.getBoolean(3));
                assertThat(rows.next()).isFalse();
                return rootHead;
            }
        }
    }

    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword());
    }

    private record RootRow(
            long sequence, String previousHash, String epochRoot, String signatureRequestId) {}

    private record RootHeadRow(long sequence, String hash, boolean sealed) {}

    private record EpochSequence(EpochIdentity epoch, long rootSequence) {}

    private record SealAttempt(long observedSequence, boolean committed) {}

    private static final class ObservedSealRepository implements AuditEpochSealRepository {

        private final AuditEpochSealRepository delegate;
        private final Instant signingTime;
        private final List<SealAttempt> attempts = new CopyOnWriteArrayList<>();

        private ObservedSealRepository(AuditEpochSealRepository delegate, Instant signingTime) {
            this.delegate = delegate;
            this.signingTime = signingTime;
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
            return delegate.insertSealAndCompareAndSwap(seal, observedRootHead)
                    .doOnNext(
                            committed ->
                                    attempts.add(
                                            new SealAttempt(
                                                    observedRootHead.sequence(), committed)));
        }

        private List<SealAttempt> attempts() {
            return List.copyOf(attempts);
        }
    }

    private static final class KillBeforeCommitRepository implements AuditEpochSealRepository {

        private final AuditEpochSealRepository delegate;
        private final Instant signingTime;
        private final AtomicInteger commitAttempts = new AtomicInteger();

        private KillBeforeCommitRepository(AuditEpochSealRepository delegate, Instant signingTime) {
            this.delegate = delegate;
            this.signingTime = signingTime;
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
            commitAttempts.incrementAndGet();
            return Mono.error(
                    new IllegalStateException("simulated termination after KMS signature"));
        }

        private AtomicInteger commitAttempts() {
            return commitAttempts;
        }
    }
}
