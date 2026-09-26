package org.meldtech.platform.shared.kernel.time;

import java.time.Instant;

@FunctionalInterface
public interface Clock {

    Instant now();
}
