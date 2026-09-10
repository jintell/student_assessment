package org.meldtech.platform.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MigrationSchemaTest {

    @Test
    void definesIndependentHistoryAndLocationForEveryOwnedSchema() {
        assertThat(MigrationSchema.values()).hasSize(15);
        assertThat(Arrays.stream(MigrationSchema.values()).map(MigrationSchema::schemaName))
                .doesNotHaveDuplicates();
        assertThat(Arrays.stream(MigrationSchema.values()).map(MigrationSchema::location))
                .allMatch(location -> location.startsWith("classpath:db/migration/"))
                .doesNotHaveDuplicates();
        assertThat(Arrays.stream(MigrationSchema.values()).map(MigrationSchema::historyTable))
                .allMatch(table -> table.startsWith("flyway_schema_history_"))
                .doesNotHaveDuplicates();
    }
}
