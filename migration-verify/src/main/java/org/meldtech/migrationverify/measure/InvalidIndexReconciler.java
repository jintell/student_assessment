package org.meldtech.migrationverify.measure;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class InvalidIndexReconciler {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern MIGRATION_VERSION = Pattern.compile("[A-Za-z0-9._-]+");
    private static final String INDEX_QUERY =
            """
            SELECT index_ns.nspname AS index_schema,
                   index_class.relname AS index_name,
                   table_ns.nspname AS table_schema,
                   table_class.relname AS table_name,
                   index_catalog.indisvalid
              FROM pg_index index_catalog
              JOIN pg_class index_class ON index_class.oid = index_catalog.indexrelid
              JOIN pg_namespace index_ns ON index_ns.oid = index_class.relnamespace
              JOIN pg_class table_class ON table_class.oid = index_catalog.indrelid
              JOIN pg_namespace table_ns ON table_ns.oid = table_class.relnamespace
             WHERE index_ns.nspname = ?
               AND index_class.relname IN (?, ?, ?)
               AND index_ns.nspname NOT IN ('pg_catalog', 'information_schema')
            """;

    public IndexReconciliationResult reconcile(
            Connection connection,
            String module,
            String failedMigrationVersion,
            String expectedQualifiedIndex)
            throws SQLException {
        IndexName expected = validate(module, failedMigrationVersion, expectedQualifiedIndex);
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(true);
        advisoryLock(connection, module, failedMigrationVersion);
        var dropped = new ArrayList<String>();
        try {
            while (true) {
                List<IndexState> indexes = findIndexes(connection, expected);
                List<IndexState> invalid = plan(expected, indexes);
                if (invalid.isEmpty()) {
                    break;
                }
                for (IndexState index : invalid) {
                    dropConcurrently(connection, index);
                    dropped.add(index.qualifiedName());
                }
            }
        } finally {
            advisoryUnlock(connection, module, failedMigrationVersion);
            connection.setAutoCommit(originalAutoCommit);
        }
        return new IndexReconciliationResult(
                dropped.isEmpty()
                        ? IndexReconciliationResult.Status.NO_ACTION
                        : IndexReconciliationResult.Status.RECONCILED,
                List.copyOf(dropped));
    }

    List<IndexState> plan(IndexName expected, List<IndexState> indexes) {
        Set<String> attributable =
                Set.of(expected.name(), expected.name() + "_ccnew", expected.name() + "_ccold");
        var invalid = new ArrayList<IndexState>();
        for (IndexState index : indexes) {
            if (!index.schema().equals(expected.schema()) || !attributable.contains(index.name())) {
                throw new IllegalStateException(
                        "Unexpected index is not attributable to the failed migration: "
                                + index.qualifiedName());
            }
            if (!index.tableSchema().equals(expected.schema())) {
                throw new IllegalStateException(
                        "Index belongs to a foreign schema table: " + index.tableSchema());
            }
            if (index.valid()) {
                throw new IllegalStateException(
                        "Refusing to drop valid index collision: " + index.qualifiedName());
            }
            invalid.add(index);
        }
        return List.copyOf(invalid);
    }

    private static List<IndexState> findIndexes(Connection connection, IndexName expected)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(INDEX_QUERY)) {
            query.setString(1, expected.schema());
            query.setString(2, expected.name());
            query.setString(3, expected.name() + "_ccnew");
            query.setString(4, expected.name() + "_ccold");
            var indexes = new ArrayList<IndexState>();
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    indexes.add(
                            new IndexState(
                                    result.getString("index_schema"),
                                    result.getString("index_name"),
                                    result.getString("table_schema"),
                                    result.getString("table_name"),
                                    result.getBoolean("indisvalid")));
                }
            }
            return List.copyOf(indexes);
        }
    }

    private static void dropConcurrently(Connection connection, IndexState index)
            throws SQLException {
        String sql =
                "DROP INDEX CONCURRENTLY IF EXISTS "
                        + quote(index.schema())
                        + "."
                        + quote(index.name());
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void advisoryLock(Connection connection, String module, String version)
            throws SQLException {
        executeAdvisory(
                connection, "SELECT pg_advisory_lock(hashtextextended(?, 0))", module, version);
    }

    private static void advisoryUnlock(Connection connection, String module, String version)
            throws SQLException {
        executeAdvisory(
                connection, "SELECT pg_advisory_unlock(hashtextextended(?, 0))", module, version);
    }

    private static void executeAdvisory(
            Connection connection, String sql, String module, String version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, module + ":" + version);
            statement.execute();
        }
    }

    private static IndexName validate(
            String module, String failedMigrationVersion, String expectedQualifiedIndex) {
        if (!IDENTIFIER.matcher(module).matches()) {
            throw new IllegalArgumentException("Invalid module identifier: " + module);
        }
        if (!MIGRATION_VERSION.matcher(failedMigrationVersion).matches()) {
            throw new IllegalArgumentException(
                    "Invalid failed migration version: " + failedMigrationVersion);
        }
        String[] parts = expectedQualifiedIndex.split("\\.", -1);
        if (parts.length != 2
                || !parts[0].equals(module)
                || !IDENTIFIER.matcher(parts[0]).matches()
                || !IDENTIFIER.matcher(parts[1]).matches()) {
            throw new IllegalArgumentException(
                    "Expected index must be schema-qualified inside module '" + module + "'");
        }
        return new IndexName(parts[0], parts[1]);
    }

    private static String quote(String identifier) {
        if (!IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Unsafe PostgreSQL identifier: " + identifier);
        }
        return '"' + identifier + '"';
    }

    record IndexName(String schema, String name) {}
}
