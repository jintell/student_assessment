package org.meldtech.migrationverify.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meldtech.migrationverify.adapter.parser.JSqlParserMigrationAdapter;

class OwnSchemaCheckTest {

    @TempDir Path directory;

    @Test
    void reportsTheDeclaredModuleAndForeignSchema() throws IOException {
        Path migration = directory.resolve("V9__foreign.sql");
        Files.writeString(
                migration,
                "-- cbt:phase EXPAND\n"
                        + "-- cbt:module delivery\n"
                        + "-- cbt:transactional true\n"
                        + "-- cbt:justification invalid cross-module change\n"
                        + "ALTER TABLE audit.audit_event ADD COLUMN source text;\n");
        var analyser =
                new MigrationAnalyser(
                        new MigrationHeaderParser(),
                        new JSqlParserMigrationAdapter(),
                        new ClosedDdlAllowlist());

        var violation =
                analyser.analyse(migration).violations().stream()
                        .filter(item -> item.code().equals("FOREIGN_SCHEMA_ACCESS"))
                        .findFirst()
                        .orElseThrow();

        assertEquals("FOREIGN_SCHEMA_ACCESS", violation.code());
        assertTrue(violation.message().contains("module 'delivery'"));
        assertTrue(violation.message().contains("foreign schema 'audit'"));
    }

    @Test
    void rejectsAnUnqualifiedRelation() throws IOException {
        Path migration = directory.resolve("V10__unqualified.sql");
        Files.writeString(
                migration,
                "-- cbt:phase EXPAND\n"
                        + "-- cbt:module delivery\n"
                        + "-- cbt:transactional true\n"
                        + "-- cbt:justification invalid unqualified change\n"
                        + "ALTER TABLE answer ADD COLUMN source text;\n");
        var analyser =
                new MigrationAnalyser(
                        new MigrationHeaderParser(),
                        new JSqlParserMigrationAdapter(),
                        new ClosedDdlAllowlist());

        var violation =
                analyser.analyse(migration).violations().stream()
                        .filter(item -> item.code().equals("FOREIGN_SCHEMA_ACCESS"))
                        .findFirst()
                        .orElseThrow();

        assertTrue(violation.message().contains("foreign schema '<unqualified>'"));
    }
}
