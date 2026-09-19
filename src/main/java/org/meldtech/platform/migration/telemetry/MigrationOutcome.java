package org.meldtech.platform.migration.telemetry;

public enum MigrationOutcome {
    SUCCESS,
    STATIC_REFUSAL,
    LOCK_THRESHOLD_FAILED,
    EXECUTION_FAILED,
    COMPATIBILITY_FAILED,
    CANCELLED
}
