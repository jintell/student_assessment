package org.meldtech.platform.platform.api;

/**
 * Committed item count and whether another batch may be useful, subject to the runner's stop gate.
 */
public record SweepBatchResult(int processedCount, boolean moreAvailable) {

    public SweepBatchResult {
        if (processedCount < 0) {
            throw new IllegalArgumentException("Sweep processed count must not be negative");
        }
    }
}
