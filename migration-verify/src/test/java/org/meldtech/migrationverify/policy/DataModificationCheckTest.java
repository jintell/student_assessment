package org.meldtech.migrationverify.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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

    @Test
    void acceptsOnlyTheApprovedSweepRegistrySeed() throws IOException {
        var analysis = analyser().analyse(migration("V10__seed.sql", approvedSweepSeed()));

        assertTrue(analysis.valid(), () -> analysis.violations().toString());
    }

    @ParameterizedTest
    @MethodSource("alteredSweepSeeds")
    void rejectsAlteredSweepRegistrySeeds(String statement) throws IOException {
        var analysis = analyser().analyse(migration("V10__altered_seed.sql", statement));

        assertTrue(
                analysis.violations().stream()
                        .anyMatch(
                                violation ->
                                        violation.code().equals("DATA_MODIFICATION_FORBIDDEN")),
                () -> analysis.violations().toString());
    }

    private static Stream<String> alteredSweepSeeds() {
        String approved = approvedSweepSeed();
        return Stream.of(
                approved.replace("8663753269692232313", "8663753269692232314"),
                approved.replace("false, 'AUTO_SUBMIT_SWEEPER'", "true, 'AUTO_SUBMIT_SWEEPER'"),
                approved.replace("'delivery', 'FEAT-DLV-004'", "'grading', 'FEAT-DLV-004'"),
                approved.replace(
                        "    ('outbox-relay'",
                        "    ('unexpected-sweep', 42, 'platform', 'FEAT-PLAT-006',\n"
                                + "     1000, false, 'OUTBOX_RELAY'),\n"
                                + "    ('outbox-relay'"),
                approved.replace("DO NOTHING", "DO UPDATE SET enabled = false"));
    }

    private static String approvedSweepSeed() {
        return """
                INSERT INTO platform.sweep_registry (
                    sweep_name, lock_key, owning_module, owning_feature,
                    default_interval_ms, enabled, system_actor
                )
                VALUES
                    ('auto-submit', 8663753269692232313, 'delivery', 'FEAT-DLV-004',
                     10000, false, 'AUTO_SUBMIT_SWEEPER'),
                    ('stale-claim-reclaim', -6495990722266513791, 'grading', 'FEAT-GRD-002',
                     30000, false, 'GRADING_WORKER'),
                    ('pin-material-destruction', -2916676166989647429, 'examaccess', 'FEAT-EXAM-002',
                     60000, false, 'RETENTION_ENGINE'),
                    ('retention', -4505843845379211855, 'audit', 'FEAT-PRIV-001',
                     60000, false, 'RETENTION_ENGINE'),
                    ('idp-reconciliation', -4891396382355818010, 'iam', 'FEAT-IAM-005',
                     60000, false, 'IDP_RECONCILER'),
                    ('outbox-relay', -2484966499978243116, 'outbox', 'FEAT-PLAT-004',
                     200, false, 'OUTBOX_RELAY')
                ON CONFLICT (sweep_name) DO NOTHING
                """;
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
                        + "-- cbt:module platform\n"
                        + "-- cbt:transactional true\n"
                        + "-- cbt:justification verify DML refusal\n"
                        + statement
                        + ";\n");
        return migration;
    }
}
