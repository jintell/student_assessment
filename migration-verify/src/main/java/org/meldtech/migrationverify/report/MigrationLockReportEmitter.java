package org.meldtech.migrationverify.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import tools.jackson.databind.json.JsonMapper;

public final class MigrationLockReportEmitter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public MigrationLockDurationReport emit(
            MigrationLockDurationReport report, Path jsonOutput, Path summaryOutput) {
        validateDataset(report.dataset());
        MigrationLockDurationReport normalized = normalize(report);
        try {
            Files.createDirectories(jsonOutput.toAbsolutePath().getParent());
            Files.createDirectories(summaryOutput.toAbsolutePath().getParent());
            byte[] json = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(normalized);
            byte[] canonical = appendLineFeed(json);
            Files.write(jsonOutput, canonical);
            Files.writeString(
                    summaryOutput,
                    summary(normalized, checksum(canonical), jsonOutput),
                    StandardCharsets.UTF_8);
            return normalized;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot emit migration lock-duration report", exception);
        }
    }

    private static MigrationLockDurationReport normalize(MigrationLockDurationReport report) {
        var statements =
                report.statements().stream()
                        .map(MigrationLockReportEmitter::sortLocks)
                        .sorted(
                                Comparator.comparing(
                                                MigrationLockDurationReport.Statement
                                                        ::migrationPath)
                                        .thenComparingInt(
                                                MigrationLockDurationReport.Statement::ordinal))
                        .toList();
        boolean failed =
                !report.sampling().complete()
                        || !report.failures().isEmpty()
                        || statements.stream()
                                .flatMap(statement -> statement.locks().stream())
                                .anyMatch(lock -> lock.verdict().equals("FAIL"));
        return new MigrationLockDurationReport(
                report.schemaVersion(),
                report.runId(),
                report.measuredAt(),
                failed ? "FAIL" : report.overallVerdict(),
                report.release(),
                report.toolchain(),
                report.dataset(),
                report.sampling(),
                report.thresholdSource(),
                statements,
                report.failures());
    }

    private static MigrationLockDurationReport.Statement sortLocks(
            MigrationLockDurationReport.Statement statement) {
        var locks =
                statement.locks().stream()
                        .sorted(
                                Comparator.comparing(MigrationLockDurationReport.Lock::relation)
                                        .thenComparing(MigrationLockDurationReport.Lock::lockMode))
                        .toList();
        return new MigrationLockDurationReport.Statement(
                statement.ordinal(),
                statement.migrationPath(),
                statement.statementChecksum(),
                statement.parsedShape(),
                statement.wallClockMs(),
                locks);
    }

    private static void validateDataset(MigrationLockDurationReport.Dataset dataset) {
        if (dataset.profileVersion() == null || dataset.profileVersion().isBlank()) {
            throw new IllegalArgumentException("Dataset profileVersion is required in the report");
        }
        if (dataset.generatorVersion() == null || dataset.generatorVersion().isBlank()) {
            throw new IllegalArgumentException(
                    "Dataset generatorVersion is required in the report");
        }
    }

    private static String summary(
            MigrationLockDurationReport report, String jsonChecksum, Path jsonOutput) {
        return """
                # Migration Lock-Duration Report

                Verdict: %s
                Release: %s (%s)
                Dataset seed: %d
                Dataset profile: %s
                JSON evidence: %s
                JSON SHA-256: %s
                """
                .formatted(
                        report.overallVerdict(),
                        report.release().id(),
                        report.release().classification(),
                        report.dataset().seed(),
                        report.dataset().profileVersion(),
                        jsonOutput.getFileName(),
                        jsonChecksum);
    }

    private static String checksum(byte[] content) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static byte[] appendLineFeed(byte[] value) {
        byte[] result = new byte[value.length + 1];
        System.arraycopy(value, 0, result, 0, value.length);
        result[value.length] = '\n';
        return result;
    }
}
