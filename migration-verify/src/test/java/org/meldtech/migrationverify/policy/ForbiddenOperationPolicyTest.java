package org.meldtech.migrationverify.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meldtech.migrationverify.adapter.parser.JSqlParserMigrationAdapter;
import org.meldtech.migrationverify.core.MigrationViolation;

class ForbiddenOperationPolicyTest {

    @TempDir Path directory;

    @Test
    void reportsEveryNamedRuleWithoutMaskingLaterChecks() throws IOException {
        Path migration =
                migration(
                        "ALTER TABLE delivery.answer RENAME COLUMN old_value TO new_value;\n"
                                + "ALTER TABLE delivery.answer ADD COLUMN required text NOT NULL;\n"
                                + "CREATE INDEX answer_idx ON delivery.answer(id);\n");
        Set<String> critical = Set.of("delivery.answer");
        var analyser =
                new MigrationAnalyser(
                        new MigrationHeaderParser(),
                        new JSqlParserMigrationAdapter(),
                        new ClosedDdlAllowlist(),
                        new ForbiddenOperationPolicy(
                                List.of(
                                        new ColumnRenameCheck(),
                                        new ColumnDropCheck(),
                                        new NotNullWithoutDefaultCheck(),
                                        new BlockingCriticalAlterCheck(critical::contains),
                                        new NonConcurrentIndexCheck())));

        var codes =
                analyser.analyse(migration).violations().stream()
                        .map(MigrationViolation::code)
                        .filter(
                                code ->
                                        Set.of(
                                                        "COLUMN_RENAME_FORBIDDEN",
                                                        "BLOCKING_CRITICAL_ALTER",
                                                        "NOT_NULL_WITHOUT_DEFAULT",
                                                        "NON_CONCURRENT_INDEX")
                                                .contains(code))
                        .toList();

        assertEquals(
                List.of(
                        "COLUMN_RENAME_FORBIDDEN",
                        "BLOCKING_CRITICAL_ALTER",
                        "NOT_NULL_WITHOUT_DEFAULT",
                        "NON_CONCURRENT_INDEX"),
                codes);
    }

    private Path migration(String statements) throws IOException {
        Path migration = directory.resolve("V8__unsafe.sql");
        Files.writeString(
                migration,
                "-- cbt:phase EXPAND\n"
                        + "-- cbt:module delivery\n"
                        + "-- cbt:transactional true\n"
                        + "-- cbt:justification exercise all checks\n"
                        + statements);
        return migration;
    }
}
