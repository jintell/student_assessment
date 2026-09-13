package org.meldtech.platform.platform.infra.persistence;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class PostgreSqlCatalogGate {

    private static final String RLS_CATALOG_QUERY =
            """
            SELECT namespace.nspname AS schema_name,
                   relation.relname AS table_name,
                   relation.relrowsecurity,
                   relation.relforcerowsecurity,
                   count(policy.oid) AS policy_count,
                   max(policy.polname) AS policy_name,
                   max(policy.polcmd::text) AS policy_command,
                   bool_and(coalesce(policy.polpermissive, false)) AS policies_permissive,
                   bool_and(coalesce(policy.polroles = ARRAY[0]::oid[], false)) AS policies_public,
                   max(pg_catalog.pg_get_expr(policy.polqual, policy.polrelid)) AS using_expression,
                   max(pg_catalog.pg_get_expr(policy.polwithcheck, policy.polrelid)) AS check_expression
            FROM pg_catalog.pg_class AS relation
            JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = relation.relnamespace
            JOIN pg_catalog.pg_attribute AS attribute ON attribute.attrelid = relation.oid
            LEFT JOIN pg_catalog.pg_policy AS policy ON policy.polrelid = relation.oid
            WHERE namespace.nspname = ANY (CAST(? AS text[]))
              AND relation.relkind IN ('r', 'p')
              AND attribute.attname = 'tenant_id'
              AND attribute.attnum > 0
              AND NOT attribute.attisdropped
            GROUP BY namespace.nspname,
                     relation.relname,
                     relation.relrowsecurity,
                     relation.relforcerowsecurity
            ORDER BY namespace.nspname, relation.relname
            """;
    private static final String CROSS_SCHEMA_FOREIGN_KEY_QUERY =
            """
            SELECT constraint_entry.conname,
                   source_namespace.nspname AS source_schema,
                   source_table.relname AS source_table,
                   target_namespace.nspname AS target_schema,
                   target_table.relname AS target_table
            FROM pg_catalog.pg_constraint AS constraint_entry
            JOIN pg_catalog.pg_class AS source_table
              ON source_table.oid = constraint_entry.conrelid
            JOIN pg_catalog.pg_namespace AS source_namespace
              ON source_namespace.oid = source_table.relnamespace
            JOIN pg_catalog.pg_class AS target_table
              ON target_table.oid = constraint_entry.confrelid
            JOIN pg_catalog.pg_namespace AS target_namespace
              ON target_namespace.oid = target_table.relnamespace
            WHERE constraint_entry.contype = 'f'
              AND source_namespace.nspname <> target_namespace.nspname
              AND (source_namespace.nspname = ANY (CAST(? AS text[]))
                   OR target_namespace.nspname = ANY (CAST(? AS text[])))
            ORDER BY source_schema, source_table, constraint_entry.conname
            """;

    private PostgreSqlCatalogGate() {}

    static void verifyForcedRls(Connection connection, List<String> applicationSchemas)
            throws SQLException {
        List<RlsTable> tenantTables = new ArrayList<>();
        Array schemas = connection.createArrayOf("text", applicationSchemas.toArray(String[]::new));
        try (var statement = connection.prepareStatement(RLS_CATALOG_QUERY)) {
            statement.setArray(1, schemas);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    tenantTables.add(
                            new RlsTable(
                                    rows.getString("schema_name"),
                                    rows.getString("table_name"),
                                    rows.getBoolean("relrowsecurity"),
                                    rows.getBoolean("relforcerowsecurity"),
                                    rows.getInt("policy_count"),
                                    rows.getString("policy_name"),
                                    rows.getString("policy_command"),
                                    rows.getBoolean("policies_permissive"),
                                    rows.getBoolean("policies_public"),
                                    rows.getString("using_expression"),
                                    rows.getString("check_expression")));
                }
            }
        } finally {
            schemas.free();
        }
        if (tenantTables.stream()
                .noneMatch(
                        table ->
                                table.schema().equals("platform")
                                        && table.table().equals("tenant_scope_probe"))) {
            throw new IllegalStateException(
                    "RLS_CATALOG_GATE: platform.tenant_scope_probe was not discovered");
        }
        List<String> violations =
                tenantTables.stream()
                        .filter(table -> !table.isCompliant())
                        .map(RlsTable::violation)
                        .toList();
        if (!violations.isEmpty()) {
            throw new IllegalStateException(String.join(System.lineSeparator(), violations));
        }
    }

    static void verifyNoCrossSchemaForeignKeys(
            Connection connection, List<String> applicationSchemas) throws SQLException {
        List<String> violations = new ArrayList<>();
        Array schemas = connection.createArrayOf("text", applicationSchemas.toArray(String[]::new));
        try (var statement = connection.prepareStatement(CROSS_SCHEMA_FOREIGN_KEY_QUERY)) {
            statement.setArray(1, schemas);
            statement.setArray(2, schemas);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    violations.add(
                            "CROSS_SCHEMA_FOREIGN_KEY_GATE: "
                                    + rows.getString("conname")
                                    + " spans "
                                    + rows.getString("source_schema")
                                    + "."
                                    + rows.getString("source_table")
                                    + " -> "
                                    + rows.getString("target_schema")
                                    + "."
                                    + rows.getString("target_table"));
                }
            }
        } finally {
            schemas.free();
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException(String.join(System.lineSeparator(), violations));
        }
    }

    private record RlsTable(
            String schema,
            String table,
            boolean enabled,
            boolean forced,
            int policyCount,
            String policyName,
            String policyCommand,
            boolean policiesPermissive,
            boolean policiesPublic,
            String usingExpression,
            String checkExpression) {

        private boolean isCompliant() {
            return enabled
                    && forced
                    && policyCount == 1
                    && "tenant_isolation".equals(policyName)
                    && "*".equals(policyCommand)
                    && policiesPermissive
                    && policiesPublic
                    && isStrictTenantPredicate(usingExpression)
                    && isStrictTenantPredicate(checkExpression);
        }

        private String violation() {
            return "RLS_CATALOG_GATE: "
                    + schema
                    + "."
                    + table
                    + " must enable and force RLS with exactly one strict tenant_isolation policy";
        }

        private static boolean isStrictTenantPredicate(String expression) {
            return expression != null
                    && expression.contains("tenant_id")
                    && expression.contains("current_setting('app.tenant_id'::text, false)")
                    && expression.contains("uuid");
        }
    }
}
