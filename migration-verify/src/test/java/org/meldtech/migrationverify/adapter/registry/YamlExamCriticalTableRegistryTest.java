package org.meldtech.migrationverify.adapter.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlExamCriticalTableRegistryTest {

    @TempDir Path directory;

    @Test
    void loadsTheSeededDeclarativeRegistry() {
        var registry =
                YamlExamCriticalTableRegistry.load(
                        Path.of("..", "migration", "exam-critical-tables.yaml"));

        assertEquals(
                Set.of(
                        "delivery.answer",
                        "delivery.answer_operation",
                        "delivery.attempt",
                        "audit.audit_event"),
                registry.relations());
        assertTrue(registry.isCritical("DELIVERY.ANSWER"));
    }

    @Test
    void rejectsAnUnqualifiedExtension() throws IOException {
        Path registry = directory.resolve("registry.yaml");
        Files.writeString(registry, "schemaVersion: 1\nrelations:\n  - answer\n");

        assertThrows(
                IllegalArgumentException.class, () -> YamlExamCriticalTableRegistry.load(registry));
    }
}
