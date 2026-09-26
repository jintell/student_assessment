package org.meldtech.platform.platform.infra.time;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class SystemClock implements org.meldtech.platform.shared.kernel.time.Clock {

    private final Clock clock;

    public SystemClock() {
        this(Clock.systemUTC());
    }

    SystemClock(java.time.Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Instant now() {
        return clock.instant();
    }
}
