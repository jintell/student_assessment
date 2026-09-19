package org.meldtech.migrationverify.measure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class LockHoldCollector {

    private final long samplingIntervalNanos;
    private final Map<LockKey, ActiveHold> active = new HashMap<>();
    private final Map<MeasurementKey, Long> maximumNanos = new HashMap<>();
    private long previousSampleNanos = -1;
    private long maximumGapNanos;
    private boolean complete = true;

    public LockHoldCollector(Duration samplingInterval) {
        if (samplingInterval.isZero() || samplingInterval.isNegative()) {
            throw new IllegalArgumentException("Sampling interval must be positive");
        }
        this.samplingIntervalNanos = samplingInterval.toNanos();
    }

    public synchronized void accept(
            int currentStatementOrdinal,
            long sampledAtNanos,
            List<ObservedRelationLock> observations) {
        if (previousSampleNanos >= 0) {
            long gap = sampledAtNanos - previousSampleNanos;
            maximumGapNanos = Math.max(maximumGapNanos, gap);
            if (!active.isEmpty() && gap > samplingIntervalNanos * 2) {
                complete = false;
            }
        }
        previousSampleNanos = sampledAtNanos;

        var observed = new HashSet<LockKey>();
        for (ObservedRelationLock observation : observations) {
            if (!observation.granted()) {
                continue;
            }
            var key =
                    new LockKey(
                            observation.relation(),
                            observation.lockMode(),
                            observation.transactionIdentity());
            observed.add(key);
            active.compute(
                    key,
                    (ignored, existing) ->
                            existing == null
                                    ? new ActiveHold(
                                            currentStatementOrdinal, sampledAtNanos, sampledAtNanos)
                                    : existing.extend(sampledAtNanos));
        }

        var released = new ArrayList<LockKey>();
        for (var entry : active.entrySet()) {
            if (!observed.contains(entry.getKey())) {
                record(entry.getKey(), entry.getValue());
                released.add(entry.getKey());
            }
        }
        released.forEach(active::remove);
    }

    public synchronized void invalidate() {
        complete = false;
    }

    public synchronized LockMeasurementResult finish(long sampledAtNanos) {
        for (var entry : active.entrySet()) {
            record(entry.getKey(), entry.getValue().extend(sampledAtNanos));
        }
        active.clear();
        List<LockHoldMeasurement> measurements =
                maximumNanos.entrySet().stream()
                        .map(
                                entry ->
                                        new LockHoldMeasurement(
                                                entry.getKey().statementOrdinal(),
                                                entry.getKey().relation(),
                                                entry.getKey().lockMode(),
                                                ceilMillis(entry.getValue())))
                        .sorted(
                                Comparator.comparingInt(LockHoldMeasurement::statementOrdinal)
                                        .thenComparing(LockHoldMeasurement::relation)
                                        .thenComparing(LockHoldMeasurement::lockMode))
                        .toList();
        return new LockMeasurementResult(measurements, ceilMillis(maximumGapNanos), complete);
    }

    private void record(LockKey lock, ActiveHold hold) {
        var key = new MeasurementKey(hold.statementOrdinal(), lock.relation(), lock.lockMode());
        long duration = hold.lastSeenNanos() - hold.firstSeenNanos() + samplingIntervalNanos;
        maximumNanos.merge(key, duration, Math::max);
    }

    private static long ceilMillis(long nanos) {
        return nanos == 0 ? 0 : Math.floorDiv(nanos - 1, 1_000_000) + 1;
    }

    private record LockKey(String relation, String lockMode, String transactionIdentity) {}

    private record MeasurementKey(int statementOrdinal, String relation, String lockMode) {}

    private record ActiveHold(int statementOrdinal, long firstSeenNanos, long lastSeenNanos) {

        private ActiveHold extend(long sampledAtNanos) {
            return new ActiveHold(statementOrdinal, firstSeenNanos, sampledAtNanos);
        }
    }
}
