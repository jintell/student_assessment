package org.meldtech.migrationverify.measure;

public record LockThresholdVerdict(
        LockHoldMeasurement measurement,
        String policy,
        Long warnMillis,
        Long failMillis,
        LockVerdict verdict) {}
