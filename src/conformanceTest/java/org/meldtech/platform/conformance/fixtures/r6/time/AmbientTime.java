package org.meldtech.platform.conformance.fixtures.r6.time;

import java.time.Instant;

public final class AmbientTime {

    public Instant now() {
        return Instant.now();
    }
}
