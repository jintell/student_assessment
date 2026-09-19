package org.meldtech.platform.migration.backfill;

public record BackfillBatch<K>(
        BackfillCheckpoint<K> checkpoint, int selectedRows, int changedRows) {

    public BackfillBatch {
        if (selectedRows < 0 || changedRows < 0 || changedRows > selectedRows) {
            throw new IllegalArgumentException("Backfill batch row counts are inconsistent");
        }
    }
}
