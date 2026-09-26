package org.meldtech.platform.platform.infra.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.time.FixedClock;

class SystemClockTest {

    @Test
    void delegatesToTheInjectedJdkClock() {
        Instant expected = Instant.parse("2026-09-25T10:15:30Z");
        SystemClock clock =
                new SystemClock(java.time.Clock.fixed(expected, java.time.ZoneOffset.UTC));

        assertEquals(expected, clock.now());
    }

    @Test
    void fixedTestClockCanAdvanceExplicitly() {
        FixedClock clock = new FixedClock(Instant.parse("2026-09-25T10:15:30Z"));

        clock.advance(Duration.ofMinutes(5));

        assertEquals(Instant.parse("2026-09-25T10:20:30Z"), clock.now());
    }
}
