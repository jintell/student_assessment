package org.meldtech.platform.migration.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.meldtech.platform.PostgreSqlTestContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackfillRehearsalIntegrationTest {

    private static final int BATCH_SIZE = 250;
    private static final int ROWS_PER_SECOND = 5_000;
    private static final int INTERRUPT_AFTER_BATCHES = 10;
    private static final long ANSWER_ACCEPTANCE_P95_LIMIT_MILLIS = 1_000;
    private static final String CONTAINER_DATASET = "/tmp/migration-fixture.csv";
    private static final BackfillDefinition DEFINITION =
            new BackfillDefinition(
                    "migration-fixture-expansion",
                    1,
                    "platform",
                    "sha256:p7.18-rehearsal-v1",
                    BATCH_SIZE,
                    ROWS_PER_SECOND);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PostgreSQLContainer postgres;
    private DatasetProfile dataset;

    @BeforeAll
    void loadProductionShapedDataset() throws IOException, SQLException {
        dataset = readDatasetProfile();
        postgres = PostgreSqlTestContainer.instance();
        postgres.start();
        postgres.copyFileToContainer(
                MountableFile.forHostPath(dataset.csvPath()), CONTAINER_DATASET);

        execute(
                """
                DROP SCHEMA IF EXISTS backfill_rehearsal CASCADE;
                CREATE SCHEMA backfill_rehearsal;
                CREATE TABLE backfill_rehearsal.migration_fixture (
                    fixture_id bigint PRIMARY KEY,
                    nullable_value text,
                    constant_default_value text,
                    constraint_candidate integer,
                    index_candidate varchar(64),
                    obsolete_value text,
                    referenced_fixture_id bigint,
                    expanded_value text,
                    update_count integer NOT NULL DEFAULT 0
                );
                CREATE TABLE backfill_rehearsal.backfill_checkpoint (
                    name text NOT NULL,
                    version integer NOT NULL,
                    definition_checksum text NOT NULL,
                    exclusive_cursor bigint,
                    upper_bound bigint NOT NULL,
                    processed_rows bigint NOT NULL,
                    PRIMARY KEY (name, version)
                );
                CREATE TABLE backfill_rehearsal.answer_acceptance_probe (
                    operation_id bigint PRIMARY KEY,
                    accepted_value text NOT NULL
                );
                COPY backfill_rehearsal.migration_fixture (
                    constant_default_value,
                    constraint_candidate,
                    fixture_id,
                    index_candidate,
                    nullable_value,
                    obsolete_value,
                    referenced_fixture_id
                ) FROM '/tmp/migration-fixture.csv'
                WITH (FORMAT csv, HEADER true, NULL '\\N');
                ANALYZE backfill_rehearsal.migration_fixture;
                """);
        assertThat(queryLong("SELECT count(*) FROM backfill_rehearsal.migration_fixture"))
                .isEqualTo(dataset.rowCount());
    }

    @Test
    void throttledBackfillInterruptsResumesAndPreservesAnswerWriteLatency() throws Exception {
        var latencies = Collections.synchronizedList(new ArrayList<Long>());
        var probeRunning = new AtomicBoolean(true);
        long startedAt = System.nanoTime();
        long persistedCursorAfterInterruption;

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> probe =
                    executor.submit(() -> recordAnswerAcceptanceLatencies(probeRunning, latencies));
            try {
                BackfillRunResult interrupted = runInterruptedBackfill();
                assertThat(interrupted.status()).isEqualTo(BackfillRunResult.Status.PAUSED);
                assertThat(interrupted.processedRows())
                        .isEqualTo((long) BATCH_SIZE * INTERRUPT_AFTER_BATCHES);
                persistedCursorAfterInterruption = queryLong(checkpointColumn("exclusive_cursor"));
                assertThat(persistedCursorAfterInterruption).isEqualTo(interrupted.processedRows());

                BackfillRunResult resumed = runToCompletion();
                assertThat(resumed.status()).isEqualTo(BackfillRunResult.Status.COMPLETED);
                assertThat(resumed.processedRows()).isEqualTo(dataset.rowCount());
            } finally {
                probeRunning.set(false);
                probe.get(10, TimeUnit.SECONDS);
            }
        }

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        long incorrectRows =
                queryLong(
                        "SELECT count(*) FROM backfill_rehearsal.migration_fixture "
                                + "WHERE expanded_value <> 'backfilled-' || fixture_id "
                                + "OR expanded_value IS NULL");
        long duplicateUpdates =
                queryLong(
                        "SELECT count(*) FROM backfill_rehearsal.migration_fixture "
                                + "WHERE update_count <> 1");
        double p95Millis = percentile95Millis(latencies);

        assertThat(incorrectRows).isZero();
        assertThat(duplicateUpdates).isZero();
        assertThat(queryLong(checkpointColumn("processed_rows"))).isEqualTo(dataset.rowCount());
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(1_900);
        assertThat(latencies).hasSizeGreaterThan(100);
        assertThat(p95Millis).isLessThanOrEqualTo(ANSWER_ACCEPTANCE_P95_LIMIT_MILLIS);

        writeReport(
                new BackfillRehearsalReport(
                        "PASS",
                        dataset.profileVersion(),
                        dataset.profileChecksum(),
                        dataset.seed(),
                        dataset.rowCount(),
                        BATCH_SIZE,
                        ROWS_PER_SECOND,
                        (long) BATCH_SIZE * INTERRUPT_AFTER_BATCHES,
                        persistedCursorAfterInterruption,
                        queryLong(checkpointColumn("exclusive_cursor")),
                        queryLong(checkpointColumn("processed_rows")),
                        incorrectRows,
                        duplicateUpdates,
                        latencies.size(),
                        p95Millis,
                        ANSWER_ACCEPTANCE_P95_LIMIT_MILLIS,
                        elapsedMillis));
    }

    private BackfillRunResult runInterruptedBackfill() {
        var permits = new AtomicInteger();
        var harness =
                new ResumableBackfillHarness<>(
                        repository(),
                        () -> Mono.just(permits.getAndIncrement() < INTERRUPT_AFTER_BATCHES),
                        new RateLimitedBackfillThrottle());
        return Objects.requireNonNull(harness.run(DEFINITION).block(Duration.ofSeconds(30)));
    }

    private BackfillRunResult runToCompletion() {
        var restartedHarness =
                new ResumableBackfillHarness<Long>(
                        repository(), () -> Mono.just(true), new RateLimitedBackfillThrottle());
        return Objects.requireNonNull(
                restartedHarness.run(DEFINITION).block(Duration.ofSeconds(30)));
    }

    private PostgresBackfillRehearsalRepository repository() {
        return new PostgresBackfillRehearsalRepository(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private void recordAnswerAcceptanceLatencies(AtomicBoolean running, List<Long> latencyNanos) {
        try (Connection connection = connection();
                var statement =
                        connection.prepareStatement(
                                "INSERT INTO backfill_rehearsal.answer_acceptance_probe "
                                        + "(operation_id, accepted_value) VALUES (?, ?) "
                                        + "ON CONFLICT (operation_id) DO UPDATE "
                                        + "SET accepted_value = EXCLUDED.accepted_value")) {
            long operation = 0;
            while (running.get()) {
                long startedAt = System.nanoTime();
                statement.setLong(1, operation % 1_000);
                statement.setString(2, "accepted-" + operation);
                statement.executeUpdate();
                latencyNanos.add(System.nanoTime() - startedAt);
                operation++;
                LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Answer-acceptance probe failed", failure);
        }
    }

    private DatasetProfile readDatasetProfile() throws IOException {
        Path reportDirectory =
                Path.of("build", "reports", "migration-stage-12", "dataset")
                        .toAbsolutePath()
                        .normalize();
        JsonNode manifest =
                objectMapper.readTree(reportDirectory.resolve("manifest.json").toFile());
        JsonNode table = findTable(manifest.path("tables"), "platform.migration_fixture");
        return new DatasetProfile(
                manifest.path("profileVersion").stringValue(),
                manifest.path("profileChecksum").stringValue(),
                manifest.path("seed").longValue(),
                table.path("rowCount").longValue(),
                reportDirectory.resolve(table.path("path").stringValue()));
    }

    private static JsonNode findTable(JsonNode tables, String tableName) {
        for (JsonNode table : tables) {
            if (tableName.equals(table.path("name").stringValue())) {
                return table;
            }
        }
        throw new IllegalStateException(
                "Generated dataset has no platform.migration_fixture table");
    }

    private static double percentile95Millis(List<Long> measurements) {
        List<Long> ordered = measurements.stream().sorted().toList();
        int index = Math.max(0, (int) Math.ceil(ordered.size() * 0.95) - 1);
        return ordered.get(index) / 1_000_000.0;
    }

    private void writeReport(BackfillRehearsalReport report) throws IOException {
        Path output =
                Path.of("build", "reports", "migration-stage-12", "backfill-rehearsal.json")
                        .toAbsolutePath()
                        .normalize();
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    private static String checkpointColumn(String column) {
        return "SELECT "
                + column
                + " FROM backfill_rehearsal.backfill_checkpoint "
                + "WHERE name = 'migration-fixture-expansion' AND version = 1";
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long queryLong(String sql) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private record DatasetProfile(
            String profileVersion,
            String profileChecksum,
            long seed,
            long rowCount,
            Path csvPath) {}

    private record BackfillRehearsalReport(
            String verdict,
            String profileVersion,
            String profileChecksum,
            long seed,
            long datasetRows,
            int batchSize,
            int rowsPerSecond,
            long interruptedAtRows,
            long persistedCursorAfterInterruption,
            long finalCursor,
            long finalProcessedRows,
            long incorrectRows,
            long duplicateUpdates,
            int latencySamples,
            double answerAcceptanceP95Millis,
            long answerAcceptanceLimitMillis,
            long elapsedMillis) {}
}
