package org.meldtech.platform.platform.infra.persistence;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PostgreSqlCatalogGate {

    private static final String RLS_CATALOG_QUERY =
            """
            SELECT namespace.nspname AS schema_name,
                   relation.relname AS table_name,
                   relation.relrowsecurity,
                   relation.relforcerowsecurity,
                   policy.polname AS policy_name,
                   policy.polcmd::text AS policy_command,
                   policy.polpermissive AS policy_permissive,
                   CASE
                       WHEN policy.polroles = ARRAY[0]::oid[] THEN ARRAY['PUBLIC']::text[]
                       ELSE ARRAY(
                           SELECT role.rolname
                           FROM unnest(policy.polroles) AS policy_role(role_oid)
                           JOIN pg_catalog.pg_roles AS role ON role.oid = policy_role.role_oid
                           ORDER BY role.rolname
                       )
                   END AS policy_roles,
                   pg_catalog.pg_get_expr(policy.polqual, policy.polrelid) AS using_expression,
                   pg_catalog.pg_get_expr(policy.polwithcheck, policy.polrelid) AS check_expression
            FROM pg_catalog.pg_class AS relation
            JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = relation.relnamespace
            JOIN pg_catalog.pg_attribute AS attribute ON attribute.attrelid = relation.oid
            LEFT JOIN pg_catalog.pg_policy AS policy ON policy.polrelid = relation.oid
            WHERE namespace.nspname = ANY (CAST(? AS text[]))
              AND relation.relkind IN ('r', 'p')
              AND NOT relation.relispartition
              AND attribute.attname = 'tenant_id'
              AND attribute.attnum > 0
              AND NOT attribute.attisdropped
            ORDER BY namespace.nspname, relation.relname, policy.polname
            """;
    private static final Set<String> OUTBOX_WRITER_ROLES =
            Set.of(
                    "app_tenancy",
                    "app_iam",
                    "app_academic",
                    "app_people",
                    "app_questionbank",
                    "app_authoring",
                    "app_examaccess",
                    "app_delivery",
                    "app_grading",
                    "app_result",
                    "app_correction",
                    "app_notification",
                    "app_txn_examentry");
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
        Map<String, RlsTableBuilder> discoveredTables = new LinkedHashMap<>();
        Array schemas = connection.createArrayOf("text", applicationSchemas.toArray(String[]::new));
        try (var statement = connection.prepareStatement(RLS_CATALOG_QUERY)) {
            statement.setArray(1, schemas);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String schema = rows.getString("schema_name");
                    String table = rows.getString("table_name");
                    boolean rlsEnabled = rows.getBoolean("relrowsecurity");
                    boolean rlsForced = rows.getBoolean("relforcerowsecurity");
                    RlsTableBuilder builder =
                            discoveredTables.computeIfAbsent(
                                    schema + "." + table,
                                    ignored ->
                                            new RlsTableBuilder(
                                                    schema, table, rlsEnabled, rlsForced));
                    String policyName = rows.getString("policy_name");
                    if (policyName != null) {
                        builder.addPolicy(
                                new RlsPolicy(
                                        policyName,
                                        rows.getString("policy_command"),
                                        rows.getBoolean("policy_permissive"),
                                        roles(rows.getArray("policy_roles")),
                                        rows.getString("using_expression"),
                                        rows.getString("check_expression")));
                    }
                }
            }
        } finally {
            schemas.free();
        }
        List<RlsTable> tenantTables =
                discoveredTables.values().stream().map(RlsTableBuilder::build).toList();
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

    private static Set<String> roles(Array value) throws SQLException {
        return value == null ? Set.of() : Set.of((String[]) value.getArray());
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
            List<RlsPolicy> policies) {

        private boolean isCompliant() {
            return enabled && forced && (isStandardTenantTable() || isOutboxTable());
        }

        private boolean isStandardTenantTable() {
            return policies.size() == 1
                    && policies.getFirst()
                            .matchesTenantPolicy("tenant_isolation", Set.of("PUBLIC"));
        }

        private boolean isOutboxTable() {
            if (!schema.equals("outbox") || !table.equals("outbox_event") || policies.size() != 2) {
                return false;
            }
            Map<String, RlsPolicy> byName =
                    policies.stream()
                            .collect(
                                    java.util.stream.Collectors.toUnmodifiableMap(
                                            RlsPolicy::name, policy -> policy));
            RlsPolicy tenantPolicy = byName.get("tenant_outbox_write");
            RlsPolicy relayPolicy = byName.get("outbox_relay_drain");
            return tenantPolicy != null
                    && tenantPolicy.matchesTenantPolicy("tenant_outbox_write", OUTBOX_WRITER_ROLES)
                    && relayPolicy != null
                    && relayPolicy.matchesRelayPolicy();
        }

        private String violation() {
            return "RLS_CATALOG_GATE: "
                    + schema
                    + "."
                    + table
                    + " must enable and force RLS with its complete strict policy contract";
        }
    }

    private record RlsPolicy(
            String name,
            String command,
            boolean permissive,
            Set<String> roles,
            String usingExpression,
            String checkExpression) {

        private boolean matchesTenantPolicy(String expectedName, Set<String> expectedRoles) {
            return name.equals(expectedName)
                    && command.equals("*")
                    && permissive
                    && roles.equals(expectedRoles)
                    && isStrictTenantPredicate(usingExpression)
                    && isStrictTenantPredicate(checkExpression);
        }

        private boolean matchesRelayPolicy() {
            return name.equals("outbox_relay_drain")
                    && command.equals("*")
                    && permissive
                    && roles.equals(Set.of("app_outbox_relay"))
                    && isStrictRelayPredicate(usingExpression)
                    && isStrictRelayPredicate(checkExpression);
        }

        private static boolean isStrictRelayPredicate(String expression) {
            if (expression == null) {
                return false;
            }
            String normalized = expression.toLowerCase(java.util.Locale.ROOT);
            return normalized.contains("current_user")
                    && normalized.contains("app_outbox_relay")
                    && normalized.contains("current_setting('app.platform_scope'::text, false)")
                    && normalized.contains("outbox_relay");
        }

        private static boolean isStrictTenantPredicate(String expression) {
            return expression != null
                    && expression.contains("tenant_id")
                    && expression.contains("current_setting('app.tenant_id'::text, false)")
                    && expression.contains("uuid");
        }
    }

    private static final class RlsTableBuilder {

        private final String schema;
        private final String table;
        private final boolean enabled;
        private final boolean forced;
        private final List<RlsPolicy> policies = new ArrayList<>();

        private RlsTableBuilder(String schema, String table, boolean enabled, boolean forced) {
            this.schema = schema;
            this.table = table;
            this.enabled = enabled;
            this.forced = forced;
        }

        private void addPolicy(RlsPolicy policy) {
            policies.add(policy);
        }

        private RlsTable build() {
            return new RlsTable(schema, table, enabled, forced, List.copyOf(policies));
        }
    }
}
