package org.meldtech.migrationverify.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationLockReportEmitterTest {

    @TempDir Path directory;

    @Test
    void emitsSeededProfiledEvidenceAndDerivesFailure() throws IOException {
        Path json = directory.resolve("lock-report.json");
        Path summary = directory.resolve("lock-report.md");
        var report = report();

        var emitted = new MigrationLockReportEmitter().emit(report, json, summary);

        assertEquals("FAIL", emitted.overallVerdict());
        String jsonText = Files.readString(json);
        assertTrue(jsonText.contains("\"seed\" : 424242"));
        assertTrue(jsonText.contains("\"profileVersion\" : \"2026.09-v1\""));
        String summaryText = Files.readString(summary);
        assertTrue(summaryText.contains("Dataset seed: 424242"));
        assertTrue(summaryText.contains("JSON SHA-256: sha256:"));
    }

    private static MigrationLockDurationReport report() {
        var lock =
                new MigrationLockDurationReport.Lock(
                        "delivery.answer",
                        "AccessExclusiveLock",
                        250,
                        0,
                        new MigrationLockDurationReport.Threshold("EXAM_CRITICAL", 100L, 250L),
                        "FAIL");
        var statement =
                new MigrationLockDurationReport.Statement(
                        1,
                        "db/migration/delivery/V2__answer.sql",
                        "sha256:statement",
                        "ALTER TABLE",
                        260,
                        List.of(lock));
        return new MigrationLockDurationReport(
                1,
                "run-1",
                "2026-09-17T12:00:00Z",
                "PASS",
                new MigrationLockDurationReport.Release(
                        "2026.09.0", "EXPAND", "sha256:manifest", "sha256:set"),
                new MigrationLockDurationReport.Toolchain(
                        "0.0.1", "jsqlparser-5.3", "postgres@sha256:image"),
                new MigrationLockDurationReport.Dataset(
                        424242,
                        1,
                        "splitmix64-v1",
                        "2026.09-v1",
                        "sha256:profile",
                        "sha256:bundle"),
                new MigrationLockDurationReport.Sampling(10, 10, true),
                new MigrationLockDurationReport.ThresholdSource(
                        "config/lock-duration-thresholds.yml", "sha256:thresholds"),
                List.of(statement),
                List.of());
    }
}
