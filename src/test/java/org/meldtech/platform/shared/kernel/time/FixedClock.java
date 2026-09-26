package org.meldtech.platform.shared.kernel.time;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class FixedClock implements Clock {

    private Instant instant;

    public FixedClock(Instant instant) {
        this.instant = Objects.requireNonNull(instant, "instant");
    }

    @Override
    public Instant now() {
        return instant;
    }

    public void advance(Duration duration) {
        instant = instant.plus(Objects.requireNonNull(duration, "duration"));
    }
}
