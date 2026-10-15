package org.meldtech.platform.platform.infra.runtime;

import java.time.Duration;
import reactor.core.publisher.Flux;

/** Package membership alone must never permit a second interval source. */
public final class UnapprovedIntervalFixture {
    public Flux<Long> ticks() {
        return Flux.interval(Duration.ofSeconds(1));
    }
}
