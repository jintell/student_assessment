package org.meldtech.migrationverify.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.meldtech.migrationverify.adapter.parser.JSqlParserMigrationAdapter;

class ForbiddenOperationPolicyTest {

    @TempDir Path directory;

    @ParameterizedTest(name = "{0}")
    @MethodSource("forbiddenOperations")
    void rejectsEachForbiddenOperationWithItsOwnMessage(
            String description, String statement, String expectedCode, String expectedMessage)
            throws IOException {
        Path migration = migration(statement);
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

        var analysis = analyser.analyse(migration);
        var violation =
                analysis.violations().stream()
                        .filter(candidate -> candidate.code().equals(expectedCode))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(analysis.violations().toString()));

        assertEquals(expectedCode, violation.code());
        assertTrue(violation.message().contains(expectedMessage), violation.message());
    }

    private static Stream<Arguments> forbiddenOperations() {
        return Stream.of(
                Arguments.of(
                        "column rename",
                        "ALTER TABLE delivery.answer RENAME COLUMN old_value TO new_value;",
                        "COLUMN_RENAME_FORBIDDEN",
                        "rename through expand, migrate, and contract columns instead"),
                Arguments.of(
                        "column drop before contract",
                        "ALTER TABLE delivery.answer DROP COLUMN old_value;",
                        "COLUMN_DROP_BEFORE_CONTRACT",
                        "only in the separate CONTRACT release"),
                Arguments.of(
                        "not null without default",
                        "ALTER TABLE delivery.answer ADD COLUMN required text NOT NULL;",
                        "NOT_NULL_WITHOUT_DEFAULT",
                        "NOT NULL requires a constant default"),
                Arguments.of(
                        "blocking alter on delivery.answer",
                        "ALTER TABLE delivery.answer ALTER COLUMN response TYPE varchar(500);",
                        "BLOCKING_CRITICAL_ALTER",
                        "blocking ALTER TABLE is forbidden on delivery.answer"),
                Arguments.of(
                        "non-concurrent index",
                        "CREATE INDEX delivery.answer_idx ON delivery.answer(id);",
                        "NON_CONCURRENT_INDEX",
                        "CREATE INDEX must use CONCURRENTLY"));
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
