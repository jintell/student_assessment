package org.meldtech.migrationverify.measure;

public record LockHoldMeasurement(
        int statementOrdinal, String relation, String lockMode, long measuredHoldMillis) {}
