package org.meldtech.migrationverify.measure;

public record LockThresholds(
        long examCriticalWarnMillis, long examCriticalFailMillis, long nonCriticalFailMillis) {

    public LockThresholds {
        if (examCriticalWarnMillis <= 0
                || examCriticalFailMillis <= examCriticalWarnMillis
                || nonCriticalFailMillis <= examCriticalFailMillis) {
            throw new IllegalArgumentException("Lock thresholds must be positive and increasing");
        }
    }
}
