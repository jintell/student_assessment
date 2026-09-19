package org.meldtech.platform.migration.backfill;

public record BackfillRunResult(Status status, long processedRows) {

    public enum Status {
        COMPLETED,
        PAUSED
    }
}
