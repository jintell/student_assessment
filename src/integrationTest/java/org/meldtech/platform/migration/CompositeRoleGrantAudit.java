package org.meldtech.platform.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import org.meldtech.platform.shared.api.AtomicCrossModuleFlow;

final class CompositeRoleGrantAudit {

    private CompositeRoleGrantAudit() {}

    static void verify(Connection connection) throws SQLException {
        Set<String> expectedRoles =
                java.util.Arrays.stream(AtomicCrossModuleFlow.values())
                        .map(AtomicCrossModuleFlow::compositeRole)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> actualRoles = new HashSet<>();
        try (var statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                """
                                SELECT rolname
                                FROM pg_catalog.pg_roles
                                WHERE rolname LIKE 'app_txn_%'
                                """)) {
            while (rows.next()) {
                actualRoles.add(rows.getString("rolname"));
            }
        }
        requireExact("composite roles", expectedRoles, actualRoles);

        Set<Membership> expectedMemberships =
                expectedRoles.stream()
                        .map(role -> new Membership("app_api", role, false, false, true))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<Membership> actualMemberships = new HashSet<>();
        try (var statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                """
                                SELECT member.rolname AS member,
                                       granted.rolname AS role,
                                       membership.admin_option,
                                       membership.inherit_option,
                                       membership.set_option
                                FROM pg_catalog.pg_auth_members AS membership
                                JOIN pg_catalog.pg_roles AS member
                                  ON member.oid = membership.member
                                JOIN pg_catalog.pg_roles AS granted
                                  ON granted.oid = membership.roleid
                                WHERE granted.rolname LIKE 'app_txn_%'
                                  -- ADMIN-only creator edges cannot assume a role in PostgreSQL 17.
                                  AND (membership.inherit_option OR membership.set_option)
                                """)) {
            while (rows.next()) {
                actualMemberships.add(
                        new Membership(
                                rows.getString("member"),
                                rows.getString("role"),
                                rows.getBoolean("admin_option"),
                                rows.getBoolean("inherit_option"),
                                rows.getBoolean("set_option")));
            }
        }
        requireExact("composite-role memberships", expectedMemberships, actualMemberships);
    }

    private static void requireExact(String label, Set<?> expected, Set<?> actual) {
        Set<Object> missing = new HashSet<>(expected);
        missing.removeAll(actual);
        Set<Object> extra = new HashSet<>(actual);
        extra.removeAll(expected);
        if (!missing.isEmpty() || !extra.isEmpty()) {
            throw new IllegalStateException(
                    "ARC-VERIFY-006 " + label + " drift: missing=" + missing + ", extra=" + extra);
        }
    }

    private record Membership(
            String member, String role, boolean admin, boolean inherit, boolean set) {}
}
