package org.meldtech.platform.platform.infra.runtime;

import java.time.Duration;

/** Monotonic, continuous pressure windows; a restart decision never clears itself. */
final class MemoryPressurePolicy {
    enum Reason {
        NONE,
        HEAP,
        CONTAINER,
        MEASUREMENT_FAILED
    }

    private long heapSince = -1;
    private long containerSince = -1;
    private Reason reason = Reason.NONE;

    synchronized Reason sample(long now, long heapUsed, long heapMax, long used, long limit) {
        if (reason != Reason.NONE) {
            return reason;
        }
        heapSince = window(heapSince, now, heapUsed >= heapMax * 0.85);
        containerSince = window(containerSince, now, used >= limit * 0.90);
        if (heapSince >= 0 && now - heapSince >= Duration.ofSeconds(60).toNanos()) {
            reason = Reason.HEAP;
        } else if (containerSince >= 0
                && now - containerSince >= Duration.ofSeconds(10).toNanos()) {
            reason = Reason.CONTAINER;
        }
        return reason;
    }

    private static long window(long since, long now, boolean exceeded) {
        return exceeded ? (since < 0 ? now : since) : -1;
    }
}
