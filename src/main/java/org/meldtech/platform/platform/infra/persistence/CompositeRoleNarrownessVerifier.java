package org.meldtech.platform.platform.infra.persistence;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class CompositeRoleNarrownessVerifier {

    private static final String COMPOSITE_ROLE = "app_txn_examentry";
    private static final Map<String, String> REPLACED_MODULE_ROLES =
            Map.of(
                    "examaccess", "app_examaccess",
                    "delivery", "app_delivery",
                    "people", "app_people",
                    "authoring", "app_authoring");
    private static final Set<String> SUPPORTING_SCHEMAS = Set.of("tenancy", "audit", "outbox");

    private CompositeRoleNarrownessVerifier() {}

    static void verify(GrantMatrix matrix) {
        Map<String, Set<GrantMatrix.Privilege>> moduleEnvelopes = moduleEnvelopes(matrix);
        Map<String, Set<GrantMatrix.Privilege>> compositePrivileges = new HashMap<>();

        for (GrantMatrix.ObjectGrant grant : matrix.objectGrants()) {
            if (!grant.grantee().equals(COMPOSITE_ROLE)
                    || grant.objectType() == GrantMatrix.ObjectType.SCHEMA) {
                continue;
            }
            if (grant.objectType() == GrantMatrix.ObjectType.ALL_TABLES_IN_SCHEMA) {
                throw new IllegalStateException(
                        "COMPOSITE_ROLE_NOT_NARROW: app_txn_examentry has a schema-wide table grant");
            }
            if (!REPLACED_MODULE_ROLES.containsKey(grant.schema())
                    && !SUPPORTING_SCHEMAS.contains(grant.schema())) {
                throw new IllegalStateException(
                        "COMPOSITE_ROLE_NOT_NARROW: undeclared schema " + grant.schema());
            }
            compositePrivileges
                    .computeIfAbsent(grant.schema(), ignored -> new HashSet<>())
                    .addAll(grant.privileges());
        }

        for (String schema : REPLACED_MODULE_ROLES.keySet()) {
            Set<GrantMatrix.Privilege> envelope = moduleEnvelopes.get(schema);
            Set<GrantMatrix.Privilege> actual = compositePrivileges.getOrDefault(schema, Set.of());
            if (envelope == null || !envelope.containsAll(actual) || envelope.equals(actual)) {
                throw new IllegalStateException(
                        "COMPOSITE_ROLE_NOT_NARROW: "
                                + schema
                                + " privileges "
                                + actual
                                + " are not a strict subset of "
                                + envelope);
            }
        }
        rejectUnsafeSupportingPrivileges(compositePrivileges);
    }

    private static Map<String, Set<GrantMatrix.Privilege>> moduleEnvelopes(GrantMatrix matrix) {
        Map<String, Set<GrantMatrix.Privilege>> envelopes = new HashMap<>();
        for (Map.Entry<String, String> module : REPLACED_MODULE_ROLES.entrySet()) {
            Set<GrantMatrix.Privilege> privileges = new HashSet<>();
            matrix.objectGrants().stream()
                    .filter(grant -> grant.grantee().equals(module.getValue()))
                    .filter(
                            grant ->
                                    grant.objectType()
                                            == GrantMatrix.ObjectType.ALL_TABLES_IN_SCHEMA)
                    .filter(grant -> grant.schema().equals(module.getKey()))
                    .forEach(grant -> privileges.addAll(grant.privileges()));
            envelopes.put(module.getKey(), Set.copyOf(privileges));
        }
        return envelopes;
    }

    private static void rejectUnsafeSupportingPrivileges(
            Map<String, Set<GrantMatrix.Privilege>> compositePrivileges) {
        Set<GrantMatrix.Privilege> tenancy = compositePrivileges.getOrDefault("tenancy", Set.of());
        if (!Set.of(GrantMatrix.Privilege.SELECT).containsAll(tenancy)) {
            throw new IllegalStateException(
                    "COMPOSITE_ROLE_NOT_NARROW: tenancy access must be read-only");
        }
        for (String appendOnlySchema : Set.of("audit", "outbox")) {
            Set<GrantMatrix.Privilege> privileges =
                    compositePrivileges.getOrDefault(appendOnlySchema, Set.of());
            if (!Set.of(GrantMatrix.Privilege.INSERT).containsAll(privileges)) {
                throw new IllegalStateException(
                        "COMPOSITE_ROLE_NOT_NARROW: "
                                + appendOnlySchema
                                + " access must be insert-only");
            }
        }
    }
}
