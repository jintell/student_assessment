package org.meldtech.migrationverify.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meldtech.migrationverify.adapter.parser.JSqlParserMigrationAdapter;

class MigrationAnalyserTest {

    @TempDir Path directory;

    private final MigrationAnalyser analyser =
            new MigrationAnalyser(
                    new MigrationHeaderParser(),
                    new JSqlParserMigrationAdapter(),
                    new ClosedDdlAllowlist());

    @Test
    void acceptsAnAllowedConcurrentExpandIndex() throws IOException {
        Path migration =
                migration(
                        "EXPAND",
                        false,
                        "CREATE INDEX CONCURRENTLY delivery.answer_candidate_idx "
                                + "ON delivery.answer(candidate_id);");

        assertTrue(analyser.analyse(migration).valid());
    }

    @Test
    void rejectsAnUnmatchedShapeAndQuotesItsParsedForm() throws IOException {
        Path migration = migration("EXPAND", true, "TRUNCATE TABLE delivery.answer;");

        var analysis = analyser.analyse(migration);

        assertEquals("DDL_SHAPE_NOT_ALLOWED", analysis.violations().getFirst().code());
        assertTrue(
                analysis.violations()
                        .getFirst()
                        .message()
                        .contains("TRUNCATE TABLE delivery.answer"));
    }

    @Test
    void rejectsAConstraintThatCanValidateWhileItIsAdded() throws IOException {
        Path migration =
                migration(
                        "EXPAND",
                        true,
                        "ALTER TABLE delivery.answer ADD CONSTRAINT positive_id CHECK (id > 0);");

        var analysis = analyser.analyse(migration);

        assertEquals("CONSTRAINT_MUST_BE_NOT_VALID", analysis.violations().getFirst().code());
    }

    @Test
    void rejectionDiagnosticsDoNotExposeLiteralValues() throws IOException {
        Path migration =
                migration(
                        "EXPAND",
                        true,
                        "UPDATE delivery.answer SET answer_text = 'private-value' WHERE id = 42;");

        String message = analyser.analyse(migration).violations().getFirst().message();

        assertTrue(!message.contains("private-value"));
        assertTrue(!message.contains("42"));
    }

    private Path migration(String phase, boolean transactional, String statement)
            throws IOException {
        Path migration = directory.resolve("V7__change.sql");
        Files.writeString(
                migration,
                "-- cbt:phase "
                        + phase
                        + "\n-- cbt:module delivery\n-- cbt:transactional "
                        + transactional
                        + "\n-- cbt:justification test migration\n"
                        + statement
                        + "\n");
        return migration;
    }
}
