package org.meldtech.migrationverify.measure;

import java.util.List;

public record LockMeasurementResult(
        List<LockHoldMeasurement> holds, long maximumObservedGapMillis, boolean complete) {}
