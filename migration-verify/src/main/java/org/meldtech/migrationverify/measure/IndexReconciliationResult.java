package org.meldtech.migrationverify.measure;

import java.util.List;

public record IndexReconciliationResult(Status status, List<String> droppedIndexes) {

    public enum Status {
        NO_ACTION,
        RECONCILED
    }
}
