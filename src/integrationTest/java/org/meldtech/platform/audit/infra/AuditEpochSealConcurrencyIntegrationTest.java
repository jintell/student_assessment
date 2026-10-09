package org.meldtech.platform.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
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

    private TenantId provisionTenant() throws SQLException {
        TenantId tenantId = TenantId.parse(UUID.randomUUID().toString());
        try (Connection connection = ownerConnection();
                var statement = connection.createStatement()) {
            statement.execute("SELECT audit.provision_audit_month('2026-09-01'::date)");
            statement.execute("SELECT audit.provision_audit_root_head('" + tenantId + "'::uuid)");
            for (RetentionClass retentionClass :
                    List.of(
                            RetentionClass.RESULT_CORRECTION_EVIDENCE,
                            RetentionClass.RESULT_PUBLICATION_EVIDENCE)) {
                statement.execute(
                        "SELECT audit.provision_audit_epoch_heads('"
                                + tenantId
                                + "'::uuid, '"
                                + retentionClass
                                + "', '2026-09-01'::date, 2, 1::smallint)");
            }
        }
        return tenantId;
    }

    private void assertLinearRootChain(TenantId tenantId) throws SQLException {
        List<RootRow> seals = new ArrayList<>();
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement(
                                """
                                SELECT root_seq,
                                       encode(previous_root_hash, 'hex'),
                                       encode(epoch_root, 'hex')
                                FROM audit.audit_chain_seal
                                WHERE tenant_id = ?
                                ORDER BY root_seq
                                """)) {
            statement.setObject(1, UUID.fromString(tenantId.toString()));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    seals.add(new RootRow(rows.getLong(1), rows.getString(2), rows.getString(3)));
                }
            }
        }

        assertThat(seals).extracting(RootRow::sequence).containsExactly(1L, 2L);
        assertThat(seals.getFirst().previousHash()).isEqualTo("00".repeat(32));
        assertThat(seals.get(1).previousHash()).isEqualTo(seals.getFirst().epochRoot());
        try (Connection connection = ownerConnection();
                var statement =
                        connection.prepareStatement(
                                """
                                SELECT root_seq, encode(root_head_hash, 'hex')
                                FROM audit.audit_chain_root_head
                                WHERE tenant_id = ?
                                """)) {
            statement.setObject(1, UUID.fromString(tenantId.toString()));
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(2L);
                assertThat(rows.getString(2)).isEqualTo(seals.get(1).epochRoot());
                assertThat(rows.next()).isFalse();
            }
        }
    }

    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword());
    }

    private record RootRow(long sequence, String previousHash, String epochRoot) {}

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
}
