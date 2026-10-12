package org.meldtech.platform.platform.infra.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

record TableScopeCatalogue(
        int formatVersion, List<NonTenantScopedPlatformTable> nonTenantScopedPlatformTables) {

    static final String RESOURCE = "db/security/table-scope-catalogue.json";

    static TableScopeCatalogue loadDefault() {
        InputStream resource =
                Objects.requireNonNull(
                        TableScopeCatalogue.class.getClassLoader().getResourceAsStream(RESOURCE),
                        () -> "Missing table-scope catalogue: " + RESOURCE);
        try (resource) {
            TableScopeCatalogue catalogue =
                    new ObjectMapper().readValue(resource, TableScopeCatalogue.class);
            catalogue.validate();
            return catalogue;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read table-scope catalogue", exception);
        }
    }

    Set<String> tableNames() {
        return nonTenantScopedPlatformTables.stream()
                .map(NonTenantScopedPlatformTable::table)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void validate() {
        if (formatVersion != 1) {
            throw new IllegalArgumentException("Unsupported table-scope catalogue formatVersion");
        }
        Set<String> names = new HashSet<>();
        for (NonTenantScopedPlatformTable entry : nonTenantScopedPlatformTables) {
            if (!entry.table().matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException("Invalid platform table name: " + entry.table());
            }
            if (!entry.owningFeature().matches("FEAT-[A-Z]+-[0-9]{3}")) {
                throw new IllegalArgumentException(
                        "Invalid owning feature: " + entry.owningFeature());
            }
            if (entry.reason().isBlank()) {
                throw new IllegalArgumentException(
                        "Table-scope reason cannot be blank: " + entry.table());
            }
            if (!names.add(entry.table())) {
                throw new IllegalArgumentException(
                        "Duplicate platform table scope: " + entry.table());
            }
        }
    }

    record NonTenantScopedPlatformTable(String table, String owningFeature, String reason) {}
}
