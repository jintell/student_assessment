package org.meldtech.platform.platform.infra.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

final class GrantMatrixLoader {

    static final String RESOURCE = "db/grants/grant-matrix.json";
    private static final Set<String> POOL_LOGIN_ROLES =
            Set.of("app_api", "app_worker", "app_pindist");
    private static final Set<String> COMPOSITE_ROLES = Set.of("app_txn_examentry");

    private final ObjectMapper objectMapper;

    GrantMatrixLoader() {
        this(new ObjectMapper());
    }

    GrantMatrixLoader(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    GrantMatrix loadDefault() {
        InputStream resource =
                Objects.requireNonNull(
                        GrantMatrixLoader.class.getClassLoader().getResourceAsStream(RESOURCE),
                        () -> "Missing grant matrix resource: " + RESOURCE);
        try (resource) {
            return load(resource);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot close grant matrix resource", exception);
        }
    }

    GrantMatrix load(InputStream input) {
        try {
            GrantMatrix matrix = objectMapper.readValue(input, GrantMatrix.class);
            validate(matrix);
            return normalized(matrix);
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new IllegalArgumentException("Invalid grant matrix", exception);
        }
    }

    private static void validate(GrantMatrix matrix) {
        require(matrix.formatVersion() == 1, "Unsupported grant matrix formatVersion");
        Set<String> schemas =
                unique(
                        matrix.schemas().stream().map(GrantMatrix.SchemaGrant::name).toList(),
                        "schema");
        Set<String> roles =
                unique(matrix.roles().stream().map(GrantMatrix.RoleGrant::name).toList(), "role");
        require(schemas.size() == 15, "Grant matrix must declare exactly 15 schemas");
        require(
                roles.containsAll(COMPOSITE_ROLES),
                "Grant matrix composite-role enumeration is incomplete");

        for (GrantMatrix.SchemaGrant schema : matrix.schemas()) {
            require(roles.contains(schema.owner()), "Unknown schema owner: " + schema.owner());
        }
        Set<String> memberships = new HashSet<>();
        for (GrantMatrix.RoleMembership membership : matrix.memberships()) {
            require(
                    roles.contains(membership.member()),
                    "Unknown membership member: " + membership.member());
            require(
                    roles.contains(membership.role()),
                    "Unknown membership role: " + membership.role());
            require(
                    memberships.add(membership.member() + "->" + membership.role()),
                    "Duplicate role membership");
        }
        rejectMembershipCycles(matrix.memberships());

        Set<String> objectFacts = new HashSet<>();
        for (GrantMatrix.ObjectGrant grant : matrix.objectGrants()) {
            require(
                    roles.contains(grant.grantee()),
                    "Unknown object-grant role: " + grant.grantee());
            require(
                    schemas.contains(grant.schema()),
                    "Unknown object-grant schema: " + grant.schema());
            require(
                    !POOL_LOGIN_ROLES.contains(grant.grantee()),
                    "Pool login roles cannot hold direct object grants");
            requireObjectName(grant.objectType(), grant.object());
            require(!grant.privileges().isEmpty(), "Object grant privileges cannot be empty");
            for (GrantMatrix.Privilege privilege : grant.privileges()) {
                validatePrivilege(grant.objectType(), privilege);
                require(
                        objectFacts.add(
                                grant.grantee()
                                        + ":"
                                        + grant.objectType()
                                        + ":"
                                        + grant.schema()
                                        + ":"
                                        + grant.object()
                                        + ":"
                                        + privilege),
                        "Duplicate object-grant fact");
            }
        }
        validateDefaultPrivileges(matrix, schemas, roles);
        validateDenials(matrix, roles, schemas);
    }

    private static void validateDefaultPrivileges(
            GrantMatrix matrix, Set<String> schemas, Set<String> roles) {
        Set<String> facts = new HashSet<>();
        for (GrantMatrix.DefaultPrivilege grant : matrix.defaultPrivileges()) {
            require(
                    roles.contains(grant.owner()),
                    "Unknown default-privilege owner: " + grant.owner());
            require(
                    schemas.contains(grant.schema()),
                    "Unknown default-privilege schema: " + grant.schema());
            require(
                    grant.objectType() == GrantMatrix.ObjectType.TABLE,
                    "Default privileges support TABLE only");
            for (String grantee : grant.grantees()) {
                require(roles.contains(grantee), "Unknown default-privilege grantee: " + grantee);
                for (GrantMatrix.Privilege privilege : grant.privileges()) {
                    validatePrivilege(grant.objectType(), privilege);
                    require(
                            facts.add(
                                    grant.owner()
                                            + ":"
                                            + grant.schema()
                                            + ":"
                                            + grantee
                                            + ":"
                                            + privilege),
                            "Duplicate default-privilege fact");
                }
            }
        }
    }

    private static void validateDenials(
            GrantMatrix matrix, Set<String> roles, Set<String> schemas) {
        Set<String> poolDenials = new HashSet<>();
        for (GrantMatrix.Denial denial : matrix.denials()) {
            require(roles.contains(denial.role()), "Unknown denial role: " + denial.role());
            if (denial.kind() == GrantMatrix.DenialKind.NO_DIRECT_OBJECT_GRANTS) {
                poolDenials.add(denial.role());
                require(
                        matrix.objectGrants().stream()
                                .noneMatch(grant -> grant.grantee().equals(denial.role())),
                        "Direct object grant contradicts denial for " + denial.role());
            } else {
                require(
                        schemas.contains(denial.schema()),
                        "Unknown denial schema: " + denial.schema());
                for (GrantMatrix.ObjectGrant grant : matrix.objectGrants()) {
                    if (grant.grantee().equals(denial.role())
                            && grant.schema().equals(denial.schema())) {
                        require(
                                grant.privileges().stream()
                                        .noneMatch(denial.privileges()::contains),
                                "Object grant contradicts schema denial for " + denial.role());
                    }
                }
            }
        }
        require(
                poolDenials.equals(POOL_LOGIN_ROLES),
                "Every pool login role must have an explicit denial");
    }

    private static void rejectMembershipCycles(List<GrantMatrix.RoleMembership> memberships) {
        Map<String, Set<String>> edges = new HashMap<>();
        memberships.forEach(
                membership ->
                        edges.computeIfAbsent(membership.member(), ignored -> new HashSet<>())
                                .add(membership.role()));
        for (String role : edges.keySet()) {
            ArrayDeque<String> pending = new ArrayDeque<>();
            pending.push(role);
            Set<String> visited = new HashSet<>();
            while (!pending.isEmpty()) {
                String current = pending.pop();
                for (String parent : edges.getOrDefault(current, Set.of())) {
                    require(!parent.equals(role), "Role-membership cycle involving " + role);
                    if (visited.add(parent)) {
                        pending.push(parent);
                    }
                }
            }
        }
    }

    private static void requireObjectName(GrantMatrix.ObjectType type, String object) {
        boolean requiresObject =
                type != GrantMatrix.ObjectType.SCHEMA
                        && type != GrantMatrix.ObjectType.ALL_TABLES_IN_SCHEMA;
        require(
                requiresObject == (object != null && !object.isBlank()),
                "Object name does not match object type");
    }

    private static void validatePrivilege(
            GrantMatrix.ObjectType type, GrantMatrix.Privilege privilege) {
        if (type == GrantMatrix.ObjectType.SCHEMA) {
            require(privilege == GrantMatrix.Privilege.USAGE, "Schema grants support USAGE only");
        } else if (type == GrantMatrix.ObjectType.VIEW) {
            require(privilege == GrantMatrix.Privilege.SELECT, "View grants support SELECT only");
        } else if (type == GrantMatrix.ObjectType.SEQUENCE) {
            require(
                    privilege == GrantMatrix.Privilege.SELECT
                            || privilege == GrantMatrix.Privilege.UPDATE
                            || privilege == GrantMatrix.Privilege.USAGE,
                    "Sequence privilege is invalid");
        } else if (type == GrantMatrix.ObjectType.FUNCTION) {
            require(
                    privilege == GrantMatrix.Privilege.EXECUTE,
                    "Function grants support EXECUTE only");
        } else {
            require(
                    privilege != GrantMatrix.Privilege.USAGE
                            && privilege != GrantMatrix.Privilege.EXECUTE,
                    "Table privilege is invalid");
        }
    }

    private static Set<String> unique(List<String> values, String label) {
        Set<String> unique = new HashSet<>(values);
        require(unique.size() == values.size(), "Duplicate " + label);
        require(
                values.stream().allMatch(GrantMatrixLoader::isIdentifier),
                "Invalid " + label + " identifier");
        return unique;
    }

    private static boolean isIdentifier(String value) {
        return value != null && value.matches("[a-z][a-z0-9_]*");
    }

    private static GrantMatrix normalized(GrantMatrix matrix) {
        return new GrantMatrix(
                matrix.formatVersion(),
                matrix.schemas().stream()
                        .sorted(java.util.Comparator.comparing(GrantMatrix.SchemaGrant::name))
                        .toList(),
                matrix.roles().stream()
                        .sorted(java.util.Comparator.comparing(GrantMatrix.RoleGrant::name))
                        .toList(),
                matrix.memberships().stream()
                        .sorted(
                                java.util.Comparator.comparing(GrantMatrix.RoleMembership::member)
                                        .thenComparing(GrantMatrix.RoleMembership::role))
                        .toList(),
                List.copyOf(matrix.objectGrants()),
                List.copyOf(matrix.defaultPrivileges()),
                List.copyOf(matrix.denials()));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
