package org.meldtech.platform.platform.infra.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class GrantDiffGate {

    private GrantDiffGate() {}

    static void verify(Connection connection, GrantMatrix matrix) throws SQLException {
        Set<Fact> expected = expectedFacts(connection, matrix);
        Set<Fact> actual = actualFacts(connection, matrix);
        Set<Fact> missing = new HashSet<>(expected);
        missing.removeAll(actual);
        Set<Fact> extra = new HashSet<>(actual);
        extra.removeAll(expected);
        if (!missing.isEmpty() || !extra.isEmpty()) {
            throw new IllegalStateException(
                    "ARC-VERIFY-002 grant drift: missing=" + missing + ", extra=" + extra);
        }
    }

    private static Set<Fact> expectedFacts(Connection connection, GrantMatrix matrix)
            throws SQLException {
        Set<Fact> facts = new HashSet<>();
        for (GrantMatrix.RoleGrant role : matrix.roles()) {
            facts.add(
                    fact(
                            "ROLE",
                            role.name(),
                            "login=" + role.login(),
                            "createRole=" + role.createRole(),
                            "inherit=false",
                            "superuser=false",
                            "createDb=false",
                            "replication=false",
                            "bypassRls=false"));
        }
        for (GrantMatrix.SchemaGrant schema : matrix.schemas()) {
            facts.add(fact("SCHEMA_OWNER", schema.name(), schema.owner()));
        }
        for (GrantMatrix.RoleMembership membership : matrix.memberships()) {
            facts.add(
                    fact(
                            "MEMBERSHIP",
                            membership.member(),
                            membership.role(),
                            "admin=false",
                            "inherit=false",
                            "set=true"));
        }

        Set<String> existingRelations = existingRelations(connection, matrix);
        for (GrantMatrix.ObjectGrant grant : matrix.objectGrants()) {
            if (grant.objectType() == GrantMatrix.ObjectType.SCHEMA) {
                grant.privileges()
                        .forEach(
                                privilege ->
                                        facts.add(
                                                fact(
                                                        "SCHEMA_PRIVILEGE",
                                                        grant.grantee(),
                                                        grant.schema(),
                                                        privilege.name())));
            } else if (grant.objectType() == GrantMatrix.ObjectType.ALL_TABLES_IN_SCHEMA) {
                existingRelations.stream()
                        .filter(name -> name.startsWith("TABLE:" + grant.schema() + "."))
                        .forEach(
                                relation ->
                                        grant.privileges()
                                                .forEach(
                                                        privilege ->
                                                                facts.add(
                                                                        fact(
                                                                                "RELATION_PRIVILEGE",
                                                                                grant.grantee(),
                                                                                relation,
                                                                                privilege
                                                                                        .name()))));
            } else if (grant.objectType() != GrantMatrix.ObjectType.FUNCTION) {
                String prefix =
                        switch (grant.objectType()) {
                            case VIEW -> "VIEW:";
                            case SEQUENCE -> "SEQUENCE:";
                            default -> "TABLE:";
                        };
                String relation = prefix + grant.schema() + "." + grant.object();
                if (existingRelations.contains(relation)) {
                    grant.privileges()
                            .forEach(
                                    privilege ->
                                            facts.add(
                                                    fact(
                                                            "RELATION_PRIVILEGE",
                                                            grant.grantee(),
                                                            relation,
                                                            privilege.name())));
                }
            } else if (routineExists(connection, grant.schema(), grant.object())) {
                grant.privileges()
                        .forEach(
                                privilege ->
                                        facts.add(
                                                fact(
                                                        "ROUTINE_PRIVILEGE",
                                                        grant.grantee(),
                                                        grant.schema() + "." + grant.object(),
                                                        privilege.name())));
            }
        }
        for (GrantMatrix.DefaultPrivilege grant : matrix.defaultPrivileges()) {
            for (String grantee : grant.grantees()) {
                for (GrantMatrix.Privilege privilege : grant.privileges()) {
                    facts.add(
                            fact(
                                    "DEFAULT_PRIVILEGE",
                                    grant.owner(),
                                    grantee,
                                    grant.schema(),
                                    grant.objectType().name(),
                                    privilege.name()));
                }
            }
        }
        return facts;
    }

    private static Set<Fact> actualFacts(Connection connection, GrantMatrix matrix)
            throws SQLException {
        Set<Fact> facts = new HashSet<>();
        collect(
                connection,
                """
                SELECT rolname, rolcanlogin, rolcreaterole, rolinherit, rolsuper,
                       rolcreatedb, rolreplication, rolbypassrls
                FROM pg_catalog.pg_roles
                WHERE rolname LIKE 'app\\_%' ESCAPE '\\'
                """,
                rows ->
                        fact(
                                "ROLE",
                                rows.getString("rolname"),
                                "login=" + rows.getBoolean("rolcanlogin"),
                                "createRole=" + rows.getBoolean("rolcreaterole"),
                                "inherit=" + rows.getBoolean("rolinherit"),
                                "superuser=" + rows.getBoolean("rolsuper"),
                                "createDb=" + rows.getBoolean("rolcreatedb"),
                                "replication=" + rows.getBoolean("rolreplication"),
                                "bypassRls=" + rows.getBoolean("rolbypassrls")),
                facts);
        collect(
                connection,
                """
                SELECT namespace.nspname, owner.rolname
                FROM pg_catalog.pg_namespace AS namespace
                JOIN pg_catalog.pg_roles AS owner ON owner.oid = namespace.nspowner
                WHERE namespace.nspname IN (%s)
                """
                        .formatted(quotedSchemas(matrix)),
                rows -> fact("SCHEMA_OWNER", rows.getString("nspname"), rows.getString("rolname")),
                facts);
        collect(
                connection,
                """
                SELECT member.rolname AS member,
                       granted.rolname AS role,
                       membership.admin_option,
                       membership.inherit_option,
                       membership.set_option
                FROM pg_catalog.pg_auth_members AS membership
                JOIN pg_catalog.pg_roles AS member ON member.oid = membership.member
                JOIN pg_catalog.pg_roles AS granted ON granted.oid = membership.roleid
                WHERE member.rolname LIKE 'app\\_%' ESCAPE '\\'
                  AND granted.rolname LIKE 'app\\_%' ESCAPE '\\'
                  -- CREATEROLE receives an unavoidable ADMIN-only edge on roles it creates.
                  AND NOT (member.rolname = 'app_migrator'
                           AND membership.admin_option
                           AND NOT membership.inherit_option
                           AND NOT membership.set_option)
                """,
                rows ->
                        fact(
                                "MEMBERSHIP",
                                rows.getString("member"),
                                rows.getString("role"),
                                "admin=" + rows.getBoolean("admin_option"),
                                "inherit=" + rows.getBoolean("inherit_option"),
                                "set=" + rows.getBoolean("set_option")),
                facts);
        collectSchemaPrivileges(connection, matrix, facts);
        collectRelationPrivileges(connection, matrix, facts);
        collectColumnPrivileges(connection, matrix, facts);
        collectRoutinePrivileges(connection, matrix, facts);
        collectDefaultPrivileges(connection, matrix, facts);
        return facts;
    }

    private static void collectSchemaPrivileges(
            Connection connection, GrantMatrix matrix, Set<Fact> facts) throws SQLException {
        collect(
                connection,
                """
                SELECT coalesce(grantee.rolname, 'PUBLIC') AS grantee,
                       namespace.nspname AS schema_name,
                       acl.privilege_type
                FROM pg_catalog.pg_namespace AS namespace
                CROSS JOIN LATERAL pg_catalog.aclexplode(namespace.nspacl) AS acl
                LEFT JOIN pg_catalog.pg_roles AS grantee ON grantee.oid = acl.grantee
                WHERE namespace.nspname IN (%s)
                  AND (acl.grantee = 0 OR grantee.rolname LIKE 'app\\_%%' ESCAPE '\\')
                  AND acl.grantee <> namespace.nspowner
                """
                        .formatted(quotedSchemas(matrix)),
                rows ->
                        fact(
                                "SCHEMA_PRIVILEGE",
                                rows.getString("grantee"),
                                rows.getString("schema_name"),
                                rows.getString("privilege_type").toUpperCase(Locale.ROOT)),
                facts);
    }

    private static void collectRelationPrivileges(
            Connection connection, GrantMatrix matrix, Set<Fact> facts) throws SQLException {
        collect(
                connection,
                """
                SELECT coalesce(grantee.rolname, 'PUBLIC') AS grantee,
                       namespace.nspname AS schema_name,
                       relation.relname AS relation_name,
                       relation.relkind,
                       acl.privilege_type
                FROM pg_catalog.pg_class AS relation
                JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = relation.relnamespace
                CROSS JOIN LATERAL pg_catalog.aclexplode(relation.relacl) AS acl
                LEFT JOIN pg_catalog.pg_roles AS grantee ON grantee.oid = acl.grantee
                WHERE namespace.nspname IN (%s)
                  AND relation.relkind IN ('r', 'p', 'v', 'm', 'S')
                  AND (acl.grantee = 0 OR grantee.rolname LIKE 'app\\_%%' ESCAPE '\\')
                  AND acl.grantee <> relation.relowner
                """
                        .formatted(quotedSchemas(matrix)),
                rows -> {
                    String type =
                            switch (rows.getString("relkind")) {
                                case "v", "m" -> "VIEW:";
                                case "S" -> "SEQUENCE:";
                                default -> "TABLE:";
                            };
                    return fact(
                            "RELATION_PRIVILEGE",
                            rows.getString("grantee"),
                            type
                                    + rows.getString("schema_name")
                                    + "."
                                    + rows.getString("relation_name"),
                            rows.getString("privilege_type").toUpperCase(Locale.ROOT));
                },
                facts);
    }

    private static void collectColumnPrivileges(
            Connection connection, GrantMatrix matrix, Set<Fact> facts) throws SQLException {
        collect(
                connection,
                """
                SELECT coalesce(grantee.rolname, 'PUBLIC') AS grantee,
                       namespace.nspname AS schema_name,
                       relation.relname AS relation_name,
                       attribute.attname AS column_name,
                       acl.privilege_type
                FROM pg_catalog.pg_attribute AS attribute
                JOIN pg_catalog.pg_class AS relation ON relation.oid = attribute.attrelid
                JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = relation.relnamespace
                CROSS JOIN LATERAL pg_catalog.aclexplode(attribute.attacl) AS acl
                LEFT JOIN pg_catalog.pg_roles AS grantee ON grantee.oid = acl.grantee
                WHERE namespace.nspname IN (%s)
                  AND (acl.grantee = 0 OR grantee.rolname LIKE 'app\\_%%' ESCAPE '\\')
                """
                        .formatted(quotedSchemas(matrix)),
                rows ->
                        fact(
                                "COLUMN_PRIVILEGE",
                                rows.getString("grantee"),
                                rows.getString("schema_name")
                                        + "."
                                        + rows.getString("relation_name")
                                        + "."
                                        + rows.getString("column_name"),
                                rows.getString("privilege_type").toUpperCase(Locale.ROOT)),
                facts);
    }

    private static void collectRoutinePrivileges(
            Connection connection, GrantMatrix matrix, Set<Fact> facts) throws SQLException {
        collect(
                connection,
                """
                SELECT coalesce(grantee.rolname, 'PUBLIC') AS grantee,
                       namespace.nspname AS schema_name,
                       routine.proname,
                       acl.privilege_type
                FROM pg_catalog.pg_proc AS routine
                JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = routine.pronamespace
                CROSS JOIN LATERAL pg_catalog.aclexplode(routine.proacl) AS acl
                LEFT JOIN pg_catalog.pg_roles AS grantee ON grantee.oid = acl.grantee
                WHERE namespace.nspname IN (%s)
                  AND (acl.grantee = 0 OR grantee.rolname LIKE 'app\\_%%' ESCAPE '\\')
                  AND acl.grantee <> routine.proowner
                """
                        .formatted(quotedSchemas(matrix)),
                rows ->
                        fact(
                                "ROUTINE_PRIVILEGE",
                                rows.getString("grantee"),
                                rows.getString("schema_name") + "." + rows.getString("proname"),
                                rows.getString("privilege_type").toUpperCase(Locale.ROOT)),
                facts);
    }

    private static void collectDefaultPrivileges(
            Connection connection, GrantMatrix matrix, Set<Fact> facts) throws SQLException {
        collect(
                connection,
                """
                SELECT owner.rolname AS owner,
                       coalesce(grantee.rolname, 'PUBLIC') AS grantee,
                       namespace.nspname AS schema_name,
                       defaults.defaclobjtype,
                       acl.privilege_type
                FROM pg_catalog.pg_default_acl AS defaults
                JOIN pg_catalog.pg_roles AS owner ON owner.oid = defaults.defaclrole
                JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = defaults.defaclnamespace
                CROSS JOIN LATERAL pg_catalog.aclexplode(defaults.defaclacl) AS acl
                LEFT JOIN pg_catalog.pg_roles AS grantee ON grantee.oid = acl.grantee
                WHERE namespace.nspname IN (%s)
                  AND (acl.grantee = 0 OR grantee.rolname LIKE 'app\\_%%' ESCAPE '\\')
                  AND acl.grantee <> defaults.defaclrole
                """
                        .formatted(quotedSchemas(matrix)),
                rows ->
                        fact(
                                "DEFAULT_PRIVILEGE",
                                rows.getString("owner"),
                                rows.getString("grantee"),
                                rows.getString("schema_name"),
                                defaultObjectType(rows.getString("defaclobjtype")),
                                rows.getString("privilege_type").toUpperCase(Locale.ROOT)),
                facts);
    }

    private static Set<String> existingRelations(Connection connection, GrantMatrix matrix)
            throws SQLException {
        Set<String> relations = new HashSet<>();
        collect(
                connection,
                """
                SELECT namespace.nspname AS schema_name, relation.relname, relation.relkind
                FROM pg_catalog.pg_class AS relation
                JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = relation.relnamespace
                WHERE namespace.nspname IN (%s)
                  AND relation.relkind IN ('r', 'p', 'v', 'm', 'S')
                """
                        .formatted(quotedSchemas(matrix)),
                rows ->
                        (rows.getString("relkind").matches("v|m")
                                        ? "VIEW:"
                                        : rows.getString("relkind").equals("S")
                                                ? "SEQUENCE:"
                                                : "TABLE:")
                                + rows.getString("schema_name")
                                + "."
                                + rows.getString("relname"),
                relations);
        return relations;
    }

    private static boolean routineExists(Connection connection, String schema, String routine)
            throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM pg_catalog.pg_proc AS routine
                            JOIN pg_catalog.pg_namespace AS namespace
                              ON namespace.oid = routine.pronamespace
                            WHERE namespace.nspname = ? AND routine.proname = ?
                        )
                        """)) {
            statement.setString(1, schema);
            statement.setString(2, routine);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }

    private static String defaultObjectType(String code) {
        return switch (code) {
            case "r" -> "TABLE";
            case "S" -> "SEQUENCE";
            case "f" -> "FUNCTION";
            default -> "UNKNOWN:" + code;
        };
    }

    private static String quotedSchemas(GrantMatrix matrix) {
        return matrix.schemas().stream()
                .map(schema -> "'" + schema.name() + "'")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static <T> void collect(
            Connection connection, String sql, SqlMapper<T> mapper, Set<T> destination)
            throws SQLException {
        try (var statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                T value = mapper.map(rows);
                if (value != null) {
                    destination.add(value);
                }
            }
        }
    }

    private static Fact fact(String... parts) {
        return new Fact(String.join("|", parts));
    }

    @FunctionalInterface
    private interface SqlMapper<T> {
        T map(ResultSet rows) throws SQLException;
    }

    private record Fact(String value) implements Comparable<Fact> {

        @Override
        public int compareTo(Fact other) {
            return value.compareTo(other.value);
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
