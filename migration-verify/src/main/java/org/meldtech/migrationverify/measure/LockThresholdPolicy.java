package org.meldtech.migrationverify.measure;

import java.util.List;
import org.meldtech.migrationverify.port.ExamCriticalRelationLookup;

public final class LockThresholdPolicy {

    private static final String ONLINE_LOCK_MODE = "ShareUpdateExclusiveLock";

    private final LockThresholds thresholds;
    private final ExamCriticalRelationLookup criticalRelations;

    public LockThresholdPolicy(
            LockThresholds thresholds, ExamCriticalRelationLookup criticalRelations) {
        this.thresholds = thresholds;
        this.criticalRelations = criticalRelations;
    }

    public List<LockThresholdVerdict> evaluate(List<LockHoldMeasurement> measurements) {
        return measurements.stream().map(this::evaluate).toList();
    }

    public LockThresholdVerdict evaluate(LockHoldMeasurement measurement) {
        if (ONLINE_LOCK_MODE.equals(measurement.lockMode())) {
            return new LockThresholdVerdict(
                    measurement, "ONLINE_COMPATIBLE", null, null, LockVerdict.INFO);
        }
        if (criticalRelations.isCritical(measurement.relation())) {
            LockVerdict verdict =
                    measurement.measuredHoldMillis() >= thresholds.examCriticalFailMillis()
                            ? LockVerdict.FAIL
                            : measurement.measuredHoldMillis()
                                            >= thresholds.examCriticalWarnMillis()
                                    ? LockVerdict.WARN
                                    : LockVerdict.PASS;
            return new LockThresholdVerdict(
                    measurement,
                    "EXAM_CRITICAL",
                    thresholds.examCriticalWarnMillis(),
                    thresholds.examCriticalFailMillis(),
                    verdict);
        }
        LockVerdict verdict =
                measurement.measuredHoldMillis() >= thresholds.nonCriticalFailMillis()
                        ? LockVerdict.FAIL
                        : LockVerdict.PASS;
        return new LockThresholdVerdict(
                measurement, "NON_CRITICAL", null, thresholds.nonCriticalFailMillis(), verdict);
    }
}
