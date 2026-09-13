package org.meldtech.platform.platform.infra.persistence;

import java.util.List;

record GrantMatrix(
        int formatVersion,
        List<SchemaGrant> schemas,
        List<RoleGrant> roles,
        List<RoleMembership> memberships,
        List<ObjectGrant> objectGrants,
        List<DefaultPrivilege> defaultPrivileges,
        List<Denial> denials) {

    enum ObjectType {
        SCHEMA,
        ALL_TABLES_IN_SCHEMA,
        TABLE,
        VIEW,
        SEQUENCE,
        FUNCTION
    }

    enum Privilege {
        USAGE,
        SELECT,
        INSERT,
        UPDATE,
        DELETE,
        EXECUTE
    }

    enum DenialKind {
        NO_DIRECT_OBJECT_GRANTS,
        NO_PRIVILEGES_IN_SCHEMA
    }

    record SchemaGrant(String name, String owner) {}

    record RoleGrant(String name, boolean login, boolean createRole) {}

    record RoleMembership(String member, String role) {}

    record ObjectGrant(
            String grantee,
            ObjectType objectType,
            String schema,
            String object,
            List<Privilege> privileges) {}

    record DefaultPrivilege(
            String owner,
            String schema,
            ObjectType objectType,
            List<String> grantees,
            List<Privilege> privileges) {}

    record Denial(DenialKind kind, String role, String schema, List<Privilege> privileges) {}
}
