package org.meldtech.migrationverify.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LockHoldCollectorTest {

    @Test
    void attributesRetainedLocksToTheAcquiringStatementAndKeepsModesDistinct() {
        var collector = new LockHoldCollector(Duration.ofMillis(10));
        long start = 1_000_000_000L;
        collector.accept(
                1, start, List.of(lock("ShareUpdateExclusiveLock"), lock("AccessExclusiveLock")));
        collector.accept(
                2,
                start + 10_000_000L,
                List.of(lock("ShareUpdateExclusiveLock"), lock("AccessExclusiveLock")));
        collector.accept(2, start + 20_000_000L, List.of());

        var result = collector.finish(start + 20_000_000L);

        assertTrue(result.complete());
        assertEquals(2, result.holds().size());
        assertEquals(1, result.holds().getFirst().statementOrdinal());
        assertEquals("AccessExclusiveLock", result.holds().getFirst().lockMode());
        assertEquals(20, result.holds().getFirst().measuredHoldMillis());
        assertEquals("ShareUpdateExclusiveLock", result.holds().getLast().lockMode());
    }

    @Test
    void marksSamplingIncompleteWhenAnActiveGapExceedsTwentyMilliseconds() {
        var collector = new LockHoldCollector(Duration.ofMillis(10));
        collector.accept(1, 0, List.of(lock("AccessExclusiveLock")));
        collector.accept(1, 21_000_000L, List.of(lock("AccessExclusiveLock")));

        assertEquals(false, collector.finish(21_000_000L).complete());
    }

    private static ObservedRelationLock lock(String mode) {
        return new ObservedRelationLock("delivery.answer", mode, "4/12", true);
    }
}
