package org.meldtech.platform.platform.infra.persistence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class GrantMatrixSqlGenerator {

    String generate(GrantMatrix matrix, String sourceSha256) {
        List<String> statements = new ArrayList<>();
        matrix.roles().stream()
                .filter(role -> !role.name().equals("app_migrator"))
                .sorted(Comparator.comparing(GrantMatrix.RoleGrant::name))
                .map(GrantMatrixSqlGenerator::roleStatement)
                .forEach(statements::add);
        matrix.memberships().stream()
                .sorted(
                        Comparator.comparing(GrantMatrix.RoleMembership::member)
                                .thenComparing(GrantMatrix.RoleMembership::role))
                .map(GrantMatrixSqlGenerator::membershipStatement)
                .forEach(statements::add);
        matrix.objectGrants().stream()
                .sorted(
                        Comparator.comparing(GrantMatrix.ObjectGrant::grantee)
                                .thenComparing(grant -> grant.objectType().name())
                                .thenComparing(GrantMatrix.ObjectGrant::schema)
                                .thenComparing(
                                        grant -> grant.object() == null ? "" : grant.object()))
                .map(GrantMatrixSqlGenerator::objectGrantStatement)
                .forEach(statements::add);
        matrix.defaultPrivileges().stream()
                .sorted(Comparator.comparing(GrantMatrix.DefaultPrivilege::schema))
                .map(GrantMatrixSqlGenerator::defaultPrivilegeStatement)
                .forEach(statements::add);
        return "-- GENERATED from db/grants/grant-matrix.json; do not edit.\n"
                + "-- source-sha256: "
                + sourceSha256
                + "\n-- Grant refresh: ${grantRefresh}"
                + "\n\n"
                + String.join("\n\n", statements)
                + "\n";
    }

    private static String roleStatement(GrantMatrix.RoleGrant role) {
        return "ALTER ROLE %s WITH %s %s NOINHERIT;"
                .formatted(
                        role.name(),
                        role.login() ? "LOGIN" : "NOLOGIN",
                        role.createRole() ? "CREATEROLE" : "NOCREATEROLE");
    }

    private static String membershipStatement(GrantMatrix.RoleMembership membership) {
        return "GRANT %s TO %s WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;"
                .formatted(membership.role(), membership.member());
    }

    private static String objectGrantStatement(GrantMatrix.ObjectGrant grant) {
        String privileges = privileges(grant.privileges());
        return switch (grant.objectType()) {
            case SCHEMA ->
                    "GRANT %s ON SCHEMA %s TO %s;"
                            .formatted(privileges, grant.schema(), grant.grantee());
            case ALL_TABLES_IN_SCHEMA ->
                    "GRANT %s ON ALL TABLES IN SCHEMA %s TO %s;"
                            .formatted(privileges, grant.schema(), grant.grantee());
            case TABLE, VIEW, SEQUENCE -> conditionalRelationGrant(grant, privileges);
            case FUNCTION ->
                    throw new IllegalArgumentException(
                            "Function grants require a signature-aware matrix entry");
        };
    }

    private static String conditionalRelationGrant(
            GrantMatrix.ObjectGrant grant, String privileges) {
        String objectType =
                grant.objectType() == GrantMatrix.ObjectType.SEQUENCE ? "SEQUENCE" : "TABLE";
        String qualifiedName = grant.schema() + "." + grant.object();
        return """
                DO $$
                BEGIN
                    IF to_regclass('%s') IS NOT NULL THEN
                        EXECUTE 'GRANT %s ON %s %s TO %s';
                    END IF;
                END
                $$;
                """
                .formatted(qualifiedName, privileges, objectType, qualifiedName, grant.grantee())
                .stripTrailing();
    }

    private static String defaultPrivilegeStatement(GrantMatrix.DefaultPrivilege grant) {
        return "ALTER DEFAULT PRIVILEGES FOR ROLE %s IN SCHEMA %s GRANT %s ON TABLES TO %s;"
                .formatted(
                        grant.owner(),
                        grant.schema(),
                        privileges(grant.privileges()),
                        String.join(", ", grant.grantees().stream().sorted().toList()));
    }

    private static String privileges(List<GrantMatrix.Privilege> privileges) {
        return privileges.stream()
                .map(Enum::name)
                .sorted()
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
