package org.meldtech.platform.migration.backfill;

import java.util.Objects;

public record BackfillDefinition(
        String name,
        int version,
        String module,
        String definitionChecksum,
        int batchSize,
        int rowsPerSecond) {

    public BackfillDefinition {
        requireText(name, "name");
        requireText(module, "module");
        requireText(definitionChecksum, "definitionChecksum");
        if (version <= 0 || batchSize <= 0 || rowsPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "Backfill version, batch size, and rows per second must be positive");
        }
    }

    private static void requireText(String value, String field) {
        if (Objects.requireNonNull(value, field).isBlank()) {
            throw new IllegalArgumentException("Backfill " + field + " must not be blank");
        }
    }
}
