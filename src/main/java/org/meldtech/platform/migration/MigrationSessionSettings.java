package org.meldtech.platform.migration;

import java.time.Duration;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;

record MigrationSessionSettings(
        Duration examCriticalLockTimeout,
        Duration nonCriticalLockTimeout,
        Duration concurrentIndexLockTimeout,
        Duration statementTimeout,
        Duration idleInTransactionTimeout) {

    private static final String PREFIX = "cbt.migration.session.";

    MigrationSessionSettings {
        requirePositive(examCriticalLockTimeout, "exam-critical-lock-timeout");
        requirePositive(nonCriticalLockTimeout, "non-critical-lock-timeout");
        requirePositive(concurrentIndexLockTimeout, "concurrent-index-lock-timeout");
        requirePositive(statementTimeout, "statement-timeout");
        requirePositive(idleInTransactionTimeout, "idle-in-transaction-timeout");
        if (examCriticalLockTimeout.compareTo(nonCriticalLockTimeout) > 0) {
            throw new IllegalArgumentException(
                    "Exam-critical lock timeout cannot exceed the non-critical timeout");
        }
    }

    static MigrationSessionSettings from(Environment environment) {
        return new MigrationSessionSettings(
                requiredDuration(environment, "exam-critical-lock-timeout"),
                requiredDuration(environment, "non-critical-lock-timeout"),
                requiredDuration(environment, "concurrent-index-lock-timeout"),
                requiredDuration(environment, "statement-timeout"),
                requiredDuration(environment, "idle-in-transaction-timeout"));
    }

    String flywayInitializationSql() {
        return ("SET lock_timeout = '%dms'; SET statement_timeout = '%dms'; "
                        + "SET idle_in_transaction_session_timeout = '%dms'")
                .formatted(
                        nonCriticalLockTimeout.toMillis(),
                        statementTimeout.toMillis(),
                        idleInTransactionTimeout.toMillis());
    }

    private static Duration requiredDuration(Environment environment, String name) {
        String value = environment.getProperty(PREFIX + name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Required migration session setting is missing: " + name);
        }
        return DurationStyle.detectAndParse(value);
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "Migration session setting must be positive: " + name);
        }
    }
}
