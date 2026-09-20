package org.meldtech.migrationverify.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meldtech.migrationverify.adapter.parser.JSqlParserMigrationAdapter;

class DataModificationCheckTest {

    @TempDir Path directory;

    @Test
    void rejectsEveryParsedRowModificationShape() throws IOException {
        List<String> statements =
                List.of(
                        "INSERT INTO delivery.answer (answer_id) VALUES (1)",
                        "UPDATE delivery.answer SET accepted = true",
                        "DELETE FROM delivery.answer",
                        "MERGE INTO delivery.answer target USING delivery.staged_answer source "
                                + "ON target.answer_id = source.answer_id "
                                + "WHEN MATCHED THEN UPDATE SET accepted = true",
                        "TRUNCATE TABLE delivery.answer");

        for (int index = 0; index < statements.size(); index++) {
            Path migration = migration("V" + (index + 1) + "__dml.sql", statements.get(index));
            var analysis = analyser().analyse(migration);

            assertTrue(
                    analysis.violations().stream()
                            .anyMatch(
                                    violation ->
                                            violation.code().equals("DATA_MODIFICATION_FORBIDDEN")),
                    statements.get(index));
        }
    }

    @Test
    void rejectsPostgreSqlCopyWithTheDataModificationCode() throws IOException {
        Path migration = migration("V6__copy.sql", "COPY delivery.answer FROM STDIN");

        var failure =
                assertThrows(IllegalArgumentException.class, () -> analyser().analyse(migration));

        assertEquals(
                "DATA_MODIFICATION_FORBIDDEN: statement 1 uses COPY; migration scripts must not modify rows",
                failure.getMessage());
    }

    private MigrationAnalyser analyser() {
        return new MigrationAnalyser(
                new MigrationHeaderParser(),
                new JSqlParserMigrationAdapter(),
                new ClosedDdlAllowlist(),
                new ForbiddenOperationPolicy(List.of(new DataModificationCheck())));
    }

    private Path migration(String fileName, String statement) throws IOException {
        Path migration = directory.resolve(fileName);
        Files.writeString(
                migration,
                "-- cbt:phase EXPAND\n"
                        + "-- cbt:module delivery\n"
                        + "-- cbt:transactional true\n"
                        + "-- cbt:justification verify DML refusal\n"
                        + statement
                        + ";\n");
        return migration;
    }
}
