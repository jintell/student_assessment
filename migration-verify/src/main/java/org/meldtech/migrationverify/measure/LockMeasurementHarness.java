package org.meldtech.migrationverify.measure;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class LockMeasurementHarness {

    public static final Duration SAMPLING_INTERVAL = Duration.ofMillis(10);

    private static final String LOCK_QUERY =
            """
            SELECT n.nspname || '.' || c.relname AS relation,
                   l.mode,
                   COALESCE(l.transactionid::text, l.virtualtransaction, '') AS transaction_identity,
                   l.granted
              FROM pg_locks l
              JOIN pg_stat_activity a ON a.pid = l.pid
              JOIN pg_class c ON c.oid = l.relation
              JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE l.pid = ? AND l.locktype = 'relation'
            """;

    public LockMeasurementResult measure(
            Connection migrator,
            Connection observer,
            List<String> statements,
            boolean transactional)
            throws SQLException {
        int backendPid = backendPid(migrator);
        var collector = new LockHoldCollector(SAMPLING_INTERVAL);
        var running = new AtomicBoolean(true);
        var statementOrdinal = new AtomicInteger();
        var sampler = Executors.newSingleThreadExecutor();
        Future<?> sampling =
                sampler.submit(
                        () -> sample(observer, backendPid, statementOrdinal, running, collector));
        boolean originalAutoCommit = migrator.getAutoCommit();
        try {
            migrator.setAutoCommit(!transactional);
            for (int index = 0; index < statements.size(); index++) {
                statementOrdinal.set(index + 1);
                try (Statement statement = migrator.createStatement()) {
                    statement.execute(statements.get(index));
                }
            }
            if (transactional) {
                migrator.commit();
            }
        } catch (SQLException exception) {
            if (transactional) {
                migrator.rollback();
            }
            throw exception;
        } finally {
            running.set(false);
            waitForSampler(sampling, collector);
            sampler.shutdownNow();
            migrator.setAutoCommit(originalAutoCommit);
        }
        return collector.finish(System.nanoTime());
    }

    private static void sample(
            Connection observer,
            int backendPid,
            AtomicInteger statementOrdinal,
            AtomicBoolean running,
            LockHoldCollector collector) {
        try (PreparedStatement query = observer.prepareStatement(LOCK_QUERY)) {
            query.setInt(1, backendPid);
            while (running.get()) {
                collector.accept(statementOrdinal.get(), System.nanoTime(), observations(query));
                Thread.sleep(SAMPLING_INTERVAL);
            }
            collector.accept(statementOrdinal.get(), System.nanoTime(), observations(query));
        } catch (SQLException | InterruptedException exception) {
            collector.invalidate();
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Lock sampling failed", exception);
        }
    }

    private static List<ObservedRelationLock> observations(PreparedStatement query)
            throws SQLException {
        var locks = new ArrayList<ObservedRelationLock>();
        try (ResultSet result = query.executeQuery()) {
            while (result.next()) {
                locks.add(
                        new ObservedRelationLock(
                                result.getString("relation"),
                                result.getString("mode"),
                                result.getString("transaction_identity"),
                                result.getBoolean("granted")));
            }
        }
        return List.copyOf(locks);
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT pg_backend_pid()")) {
            if (!result.next()) {
                throw new SQLException("PostgreSQL did not return a migrator backend PID");
            }
            return result.getInt(1);
        }
    }

    private static void waitForSampler(Future<?> sampling, LockHoldCollector collector) {
        try {
            sampling.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            collector.invalidate();
        } catch (ExecutionException exception) {
            collector.invalidate();
        }
    }
}
