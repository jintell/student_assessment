package org.meldtech.platform.migration.backfill;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import reactor.core.publisher.Mono;

final class PostgresBackfillRehearsalRepository implements BackfillRepository<Long> {

    private static final String SCHEMA = "backfill_rehearsal";

    private final String jdbcUrl;
    private final String username;
    private final String password;

    PostgresBackfillRehearsalRepository(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    public Mono<BackfillCheckpoint<Long>> loadOrCreate(BackfillDefinition definition) {
        return Mono.fromCallable(() -> initializeCheckpoint(definition));
    }

    @Override
    public Mono<BackfillBatch<Long>> processNextBatch(
            BackfillDefinition definition, BackfillCheckpoint<Long> checkpoint) {
        return Mono.fromCallable(() -> processBatch(definition, checkpoint));
    }

    @Override
    public Mono<Boolean> hasEligibleRowsAtOrBelow(
            BackfillDefinition definition, BackfillCheckpoint<Long> checkpoint) {
        return Mono.fromCallable(
                () -> {
                    try (Connection connection = connection();
                            var statement =
                                    connection.prepareStatement(
                                            "SELECT EXISTS ("
                                                    + "SELECT 1 FROM "
                                                    + SCHEMA
                                                    + ".migration_fixture "
                                                    + "WHERE fixture_id <= ? "
                                                    + "AND expanded_value IS NULL)")) {
                        statement.setLong(1, checkpoint.upperBound());
                        try (ResultSet result = statement.executeQuery()) {
                            result.next();
                            return result.getBoolean(1);
                        }
                    }
                });
    }

    private BackfillCheckpoint<Long> initializeCheckpoint(BackfillDefinition definition)
            throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                long upperBound = upperBound(connection);
                try (var statement =
                        connection.prepareStatement(
                                "INSERT INTO "
                                        + SCHEMA
                                        + ".backfill_checkpoint "
                                        + "(name, version, definition_checksum, upper_bound, "
                                        + "processed_rows) VALUES (?, ?, ?, ?, 0) "
                                        + "ON CONFLICT (name, version) DO NOTHING")) {
                    statement.setString(1, definition.name());
                    statement.setInt(2, definition.version());
                    statement.setString(3, definition.definitionChecksum());
                    statement.setLong(4, upperBound);
                    statement.executeUpdate();
                }
                BackfillCheckpoint<Long> checkpoint = readCheckpoint(connection, definition);
                connection.commit();
                return checkpoint;
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private BackfillBatch<Long> processBatch(
            BackfillDefinition definition, BackfillCheckpoint<Long> expected) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                BackfillCheckpoint<Long> current = lockCheckpoint(connection, definition);
                requireExpectedCheckpoint(expected, current);
                List<Long> selected = selectBatch(connection, definition, current);
                int changedRows = updateSelectedRows(connection, selected);
                BackfillCheckpoint<Long> next =
                        advanceCheckpoint(connection, definition, current, selected);
                connection.commit();
                return new BackfillBatch<>(next, selected.size(), changedRows);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static long upperBound(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "SELECT max(fixture_id) FROM " + SCHEMA + ".migration_fixture")) {
            result.next();
            long upperBound = result.getLong(1);
            if (result.wasNull()) {
                throw new IllegalStateException("Backfill rehearsal dataset is empty");
            }
            return upperBound;
        }
    }

    private static BackfillCheckpoint<Long> lockCheckpoint(
            Connection connection, BackfillDefinition definition) throws SQLException {
        return readCheckpoint(connection, definition, " FOR UPDATE");
    }

    private static BackfillCheckpoint<Long> readCheckpoint(
            Connection connection, BackfillDefinition definition) throws SQLException {
        return readCheckpoint(connection, definition, "");
    }

    private static BackfillCheckpoint<Long> readCheckpoint(
            Connection connection, BackfillDefinition definition, String lockClause)
            throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        "SELECT definition_checksum, exclusive_cursor, upper_bound, processed_rows "
                                + "FROM "
                                + SCHEMA
                                + ".backfill_checkpoint WHERE name = ? AND version = ?"
                                + lockClause)) {
            statement.setString(1, definition.name());
            statement.setInt(2, definition.version());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("Backfill checkpoint was not initialized");
                }
                long cursor = result.getLong("exclusive_cursor");
                Optional<Long> exclusiveCursor =
                        result.wasNull() ? Optional.empty() : Optional.of(cursor);
                return new BackfillCheckpoint<>(
                        result.getString("definition_checksum"),
                        exclusiveCursor,
                        result.getLong("upper_bound"),
                        result.getLong("processed_rows"));
            }
        }
    }

    private static void requireExpectedCheckpoint(
            BackfillCheckpoint<Long> expected, BackfillCheckpoint<Long> current) {
        if (!expected.equals(current)) {
            throw new IllegalStateException("Backfill checkpoint changed concurrently");
        }
    }

    private static List<Long> selectBatch(
            Connection connection,
            BackfillDefinition definition,
            BackfillCheckpoint<Long> checkpoint)
            throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        "SELECT fixture_id FROM "
                                + SCHEMA
                                + ".migration_fixture "
                                + "WHERE fixture_id > ? AND fixture_id <= ? "
                                + "ORDER BY fixture_id LIMIT ?")) {
            statement.setLong(1, checkpoint.exclusiveCursor().orElse(0L));
            statement.setLong(2, checkpoint.upperBound());
            statement.setInt(3, definition.batchSize());
            try (ResultSet result = statement.executeQuery()) {
                List<Long> selected = new ArrayList<>(definition.batchSize());
                while (result.next()) {
                    selected.add(result.getLong(1));
                }
                return List.copyOf(selected);
            }
        }
    }

    private static int updateSelectedRows(Connection connection, List<Long> selected)
            throws SQLException {
        if (selected.isEmpty()) {
            return 0;
        }
        try (var statement =
                connection.prepareStatement(
                        "UPDATE "
                                + SCHEMA
                                + ".migration_fixture "
                                + "SET expanded_value = 'backfilled-' || fixture_id, "
                                + "update_count = update_count + 1 "
                                + "WHERE fixture_id = ? AND expanded_value IS NULL")) {
            for (Long key : selected) {
                statement.setLong(1, key);
                statement.addBatch();
            }
            int changedRows = 0;
            for (int result : statement.executeBatch()) {
                changedRows += result;
            }
            return changedRows;
        }
    }

    private static BackfillCheckpoint<Long> advanceCheckpoint(
            Connection connection,
            BackfillDefinition definition,
            BackfillCheckpoint<Long> current,
            List<Long> selected)
            throws SQLException {
        if (selected.isEmpty()) {
            return current;
        }
        long cursor = selected.getLast();
        long processedRows = current.processedRows() + selected.size();
        try (var statement =
                connection.prepareStatement(
                        "UPDATE "
                                + SCHEMA
                                + ".backfill_checkpoint "
                                + "SET exclusive_cursor = ?, processed_rows = ? "
                                + "WHERE name = ? AND version = ?")) {
            statement.setLong(1, cursor);
            statement.setLong(2, processedRows);
            statement.setString(3, definition.name());
            statement.setInt(4, definition.version());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Backfill checkpoint update failed");
            }
        }
        return new BackfillCheckpoint<>(
                current.definitionChecksum(),
                Optional.of(cursor),
                current.upperBound(),
                processedRows);
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }
}
