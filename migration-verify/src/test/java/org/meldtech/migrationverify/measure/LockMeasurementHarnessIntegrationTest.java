package org.meldtech.migrationverify.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LockMeasurementHarnessIntegrationTest {

    private Stage12Database database;

    @BeforeAll
    void startDatabase() throws IOException, SQLException {
        database = new Stage12Database(pinnedImage(), 10_000);
        database.start();
        try (Connection connection = connection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA platform");
            statement.execute(
                    "CREATE TABLE platform.migration_fixture "
                            + "(fixture_id bigint PRIMARY KEY, payload text NOT NULL)");
            statement.execute(
                    "INSERT INTO platform.migration_fixture "
                            + "SELECT value, 'fixture-' || value FROM generate_series(1, 10000) value");
        }
    }

    @AfterAll
    void stopDatabase() {
        database.close();
    }

    @Test
    void blockingAlterUnderConcurrentDmlExceedsThresholdAndFailsGate() throws Exception {
        var dmlStarted = new CountDownLatch(1);
        var keepRunning = new AtomicBoolean(true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                Connection migrator = connection();
                Connection observer = connection()) {
            Future<?> dml = executor.submit(() -> runConcurrentDml(dmlStarted, keepRunning));
            dmlStarted.await();

            var measurement =
                    new LockMeasurementHarness()
                            .measure(
                                    migrator,
                                    observer,
                                    List.of(
                                            "ALTER TABLE platform.migration_fixture "
                                                    + "ADD COLUMN blocked_value text",
                                            "SELECT pg_sleep(0.35)"),
                                    true);

            keepRunning.set(false);
            dml.get();

            LockHoldMeasurement blockingHold =
                    measurement.holds().stream()
                            .filter(
                                    hold ->
                                            hold.relation().equals("platform.migration_fixture")
                                                    && hold.lockMode()
                                                            .equals("AccessExclusiveLock"))
                            .findFirst()
                            .orElseThrow();
            var policy =
                    new LockThresholdPolicy(
                            new LockThresholds(100, 250, 2_000),
                            relation -> relation.equals("platform.migration_fixture"));

            assertTrue(blockingHold.measuredHoldMillis() >= 250, blockingHold.toString());
            assertEquals(LockVerdict.FAIL, policy.evaluate(blockingHold).verdict());
        } finally {
            keepRunning.set(false);
        }
    }

    @Test
    void concurrentIndexUnderConcurrentDmlDoesNotFailTheGate() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                Connection migrator = connection();
                Connection observer = connection();
                Connection dml = connection();
                Connection lockProbe = connection()) {
            int migratorPid = backendPid(migrator);
            dml.setAutoCommit(false);
            try (var update =
                    dml.prepareStatement(
                            "UPDATE platform.migration_fixture "
                                    + "SET payload = payload || '' WHERE fixture_id = 2")) {
                update.executeUpdate();
            }

            Future<LockMeasurementResult> pendingMeasurement =
                    executor.submit(
                            () ->
                                    new LockMeasurementHarness()
                                            .measure(
                                                    migrator,
                                                    observer,
                                                    List.of(
                                                            "CREATE INDEX CONCURRENTLY "
                                                                    + "migration_fixture_payload_idx "
                                                                    + "ON platform.migration_fixture (payload)"),
                                                    false));

            awaitGrantedLock(
                    lockProbe,
                    migratorPid,
                    "platform.migration_fixture",
                    "ShareUpdateExclusiveLock");
            dml.commit();
            LockMeasurementResult measurement = pendingMeasurement.get();
            LockHoldMeasurement onlineHold =
                    measurement.holds().stream()
                            .filter(
                                    hold ->
                                            hold.relation().equals("platform.migration_fixture")
                                                    && hold.lockMode()
                                                            .equals("ShareUpdateExclusiveLock"))
                            .findFirst()
                            .orElseThrow();
            var policy =
                    new LockThresholdPolicy(
                            new LockThresholds(100, 250, 2_000),
                            relation -> relation.equals("platform.migration_fixture"));

            assertTrue(onlineHold.measuredHoldMillis() > 0, onlineHold.toString());
            assertEquals(LockVerdict.INFO, policy.evaluate(onlineHold).verdict());
        }
    }

    @Test
    void lockTimeoutFailsFastInsteadOfQueueingBehindAConflictingLock() throws Exception {
        try (Connection blocker = connection();
                Connection migrator = connection()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.createStatement()) {
                lock.execute("LOCK TABLE platform.migration_fixture IN ACCESS SHARE MODE");
            }
            try (var settings = migrator.createStatement()) {
                settings.execute("SET lock_timeout = '250ms'");
            }

            long startedAt = System.nanoTime();
            SQLException failure =
                    assertThrows(
                            SQLException.class,
                            () -> {
                                try (var alter = migrator.createStatement()) {
                                    alter.execute(
                                            "ALTER TABLE platform.migration_fixture "
                                                    + "ADD COLUMN timeout_probe text");
                                }
                            });
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            assertEquals("55P03", failure.getSQLState());
            assertTrue(failure.getMessage().contains("lock timeout"), failure.getMessage());
            assertTrue(elapsedMillis >= 200, "lock timeout fired too early: " + elapsedMillis);
            assertTrue(elapsedMillis < 1_500, "lock wait was not bounded: " + elapsedMillis);
            blocker.rollback();
        }
    }

    @Test
    void interruptedConcurrentIndexIsDetectedReconciledAndRetryable() throws Exception {
        String indexName = "migration_fixture_interrupted_idx";
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                Connection migrator = connection();
                Connection dml = connection();
                Connection probe = connection()) {
            int migratorPid = backendPid(migrator);
            dml.setAutoCommit(false);
            try (var update =
                    dml.prepareStatement(
                            "UPDATE platform.migration_fixture "
                                    + "SET payload = payload || '' WHERE fixture_id = 3")) {
                update.executeUpdate();
            }

            Future<?> pendingIndex =
                    executor.submit(
                            () -> {
                                try (var create = migrator.createStatement()) {
                                    create.execute(
                                            "CREATE INDEX CONCURRENTLY "
                                                    + indexName
                                                    + " ON platform.migration_fixture (fixture_id, payload)");
                                }
                                return null;
                            });

            awaitIndexValidity(probe, "platform", indexName, false);
            try (PreparedStatement cancel = probe.prepareStatement("SELECT pg_cancel_backend(?)")) {
                cancel.setInt(1, migratorPid);
                try (ResultSet result = cancel.executeQuery()) {
                    result.next();
                    assertTrue(result.getBoolean(1), "PostgreSQL did not cancel the index builder");
                }
            }
            ExecutionException cancellation =
                    assertThrows(ExecutionException.class, pendingIndex::get);
            assertTrue(cancellation.getCause() instanceof SQLException, cancellation.toString());
            dml.rollback();

            assertEquals(false, indexValidity(probe, "platform", indexName).orElseThrow());
            IndexReconciliationResult reconciliation =
                    new InvalidIndexReconciler()
                            .reconcile(
                                    migrator,
                                    "platform",
                                    "V-test-interrupted-index",
                                    "platform." + indexName);

            assertEquals(IndexReconciliationResult.Status.RECONCILED, reconciliation.status());
            assertEquals(List.of("platform." + indexName), reconciliation.droppedIndexes());
            assertTrue(indexValidity(probe, "platform", indexName).isEmpty());

            try (var retry = migrator.createStatement()) {
                retry.execute(
                        "CREATE INDEX CONCURRENTLY "
                                + indexName
                                + " ON platform.migration_fixture (fixture_id, payload)");
                retry.execute("DROP INDEX CONCURRENTLY platform." + indexName);
            }
        }
    }

    private void runConcurrentDml(CountDownLatch started, AtomicBoolean keepRunning) {
        try (Connection connection = connection();
                var statement =
                        connection.prepareStatement(
                                "UPDATE platform.migration_fixture SET payload = payload || '' WHERE fixture_id = 1")) {
            statement.executeUpdate();
            started.countDown();
            while (keepRunning.get()) {
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Concurrent DML failed", exception);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password());
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT pg_backend_pid()")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void awaitGrantedLock(
            Connection probe, int backendPid, String relation, String mode) throws Exception {
        String sql =
                """
                SELECT EXISTS (
                    SELECT 1
                      FROM pg_locks locks
                      JOIN pg_class relation_class ON relation_class.oid = locks.relation
                      JOIN pg_namespace namespace ON namespace.oid = relation_class.relnamespace
                     WHERE locks.pid = ?
                       AND namespace.nspname || '.' || relation_class.relname = ?
                       AND locks.mode = ?
                       AND locks.granted
                )
                """;
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        try (PreparedStatement statement = probe.prepareStatement(sql)) {
            statement.setInt(1, backendPid);
            statement.setString(2, relation);
            statement.setString(3, mode);
            while (System.nanoTime() < deadline) {
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    if (result.getBoolean(1)) {
                        return;
                    }
                }
                Thread.sleep(10);
            }
        }
        throw new AssertionError("Timed out waiting for " + mode + " on " + relation);
    }

    private static void awaitIndexValidity(
            Connection connection, String schema, String index, boolean expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Boolean> validity = indexValidity(connection, schema, index);
            if (validity.isPresent() && validity.get() == expected) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(
                "Timed out waiting for " + schema + "." + index + " validity=" + expected);
    }

    private static Optional<Boolean> indexValidity(
            Connection connection, String schema, String index) throws SQLException {
        String sql =
                """
                SELECT catalog.indisvalid
                  FROM pg_index catalog
                  JOIN pg_class index_class ON index_class.oid = catalog.indexrelid
                  JOIN pg_namespace namespace ON namespace.oid = index_class.relnamespace
                 WHERE namespace.nspname = ? AND index_class.relname = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, index);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getBoolean(1)) : Optional.empty();
            }
        }
    }

    private static String pinnedImage() throws IOException {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("..", "gradle.properties"))) {
            properties.load(reader);
        }
        return properties.getProperty("postgresqlImage");
    }
}
