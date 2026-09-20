package org.meldtech.migrationverify.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.meldtech.migrationverify.adapter.parser.JSqlParserMigrationAdapter;

class MigrationAnalyserTest {

    @TempDir Path directory;

    private final MigrationAnalyser analyser =
            new MigrationAnalyser(
                    new MigrationHeaderParser(),
                    new JSqlParserMigrationAdapter(),
                    new ClosedDdlAllowlist());

    @ParameterizedTest(name = "{0}")
    @MethodSource("permittedShapes")
    void acceptsEveryPermittedShape(
            String description, String phase, boolean transactional, String statement)
            throws IOException {
        Path migration = migration(phase, transactional, statement);

        var analysis = analyser.analyse(migration);

        assertTrue(analysis.valid(), () -> analysis.violations().toString());
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

        assertTrue(message.contains("SET answer_text = '?' WHERE id = ?"));
        assertFalse(message.contains("'private-value'"));
        assertFalse(message.contains("id = 42"));
    }

    private static Stream<Arguments> permittedShapes() {
        return Stream.of(
                Arguments.of(
                        "EXPAND create table",
                        "EXPAND",
                        true,
                        "CREATE TABLE delivery.new_answer (id bigint);"),
                Arguments.of(
                        "EXPAND add column",
                        "EXPAND",
                        true,
                        "ALTER TABLE delivery.answer ADD COLUMN source text;"),
                Arguments.of(
                        "EXPAND add constraint not valid",
                        "EXPAND",
                        true,
                        "ALTER TABLE delivery.answer ADD CONSTRAINT positive_id "
                                + "CHECK (id > 0) NOT VALID;"),
                Arguments.of(
                        "EXPAND create index concurrently",
                        "EXPAND",
                        false,
                        "CREATE INDEX CONCURRENTLY delivery.answer_candidate_idx "
                                + "ON delivery.answer(candidate_id);"),
                Arguments.of(
                        "EXPAND comment after create",
                        "EXPAND",
                        true,
                        "CREATE TABLE delivery.new_answer (id bigint);\n"
                                + "COMMENT ON TABLE delivery.new_answer IS 'answer staging';"),
                Arguments.of(
                        "MIGRATE validate constraint",
                        "MIGRATE",
                        true,
                        "ALTER TABLE delivery.answer VALIDATE CONSTRAINT positive_id;"),
                Arguments.of(
                        "MIGRATE set default",
                        "MIGRATE",
                        true,
                        "ALTER TABLE delivery.answer ALTER COLUMN source SET DEFAULT 'unknown';"),
                Arguments.of(
                        "CONTRACT drop column",
                        "CONTRACT",
                        true,
                        "ALTER TABLE delivery.answer DROP COLUMN obsolete_source;"),
                Arguments.of(
                        "CONTRACT drop constraint",
                        "CONTRACT",
                        true,
                        "ALTER TABLE delivery.answer DROP CONSTRAINT obsolete_check;"),
                Arguments.of(
                        "CONTRACT drop default",
                        "CONTRACT",
                        true,
                        "ALTER TABLE delivery.answer ALTER COLUMN source DROP DEFAULT;"),
                Arguments.of(
                        "CONTRACT drop index concurrently",
                        "CONTRACT",
                        false,
                        "DROP INDEX CONCURRENTLY delivery.answer_candidate_idx;"),
                Arguments.of(
                        "CONTRACT drop table",
                        "CONTRACT",
                        true,
                        "DROP TABLE delivery.obsolete_answer;"));
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
