package org.meldtech.migrationverify.report;

import java.util.List;

public record MigrationLockDurationReport(
        int schemaVersion,
        String runId,
        String measuredAt,
        String overallVerdict,
        Release release,
        Toolchain toolchain,
        Dataset dataset,
        Sampling sampling,
        ThresholdSource thresholdSource,
        List<Statement> statements,
        List<Failure> failures) {

    public MigrationLockDurationReport {
        statements = List.copyOf(statements);
        failures = List.copyOf(failures);
    }

    public record Release(
            String id,
            String classification,
            String manifestChecksum,
            String migrationSetChecksum) {}

    public record Toolchain(
            String migrationVerifyVersion, String parserVersion, String postgresImageDigest) {}

    public record Dataset(
            long seed,
            int scale,
            String generatorVersion,
            String profileVersion,
            String profileChecksum,
            String bundleChecksum) {}

    public record Sampling(long intervalMs, long maximumObservedGapMs, boolean complete) {}

    public record ThresholdSource(String path, String checksum) {}

    public record Statement(
            int ordinal,
            String migrationPath,
            String statementChecksum,
            String parsedShape,
            long wallClockMs,
            List<Lock> locks) {

        public Statement {
            locks = List.copyOf(locks);
        }
    }

    public record Lock(
            String relation,
            String lockMode,
            long measuredHoldMs,
            long acquisitionWaitMs,
            Threshold threshold,
            String verdict) {}

    public record Threshold(String policy, Long warnMs, Long failMs) {}

    public record Failure(
            String code,
            String migrationPath,
            int statementOrdinal,
            String relation,
            String message) {}
}
