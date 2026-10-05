package org.meldtech.platform.conformance.fixtures.telemetry.slice.invalid;

import io.micrometer.core.instrument.Counter;

final class DirectBusinessMetric {

    private final Counter counter;

    DirectBusinessMetric(Counter counter) {
        this.counter = counter;
    }

    void record() {
        counter.increment();
    }
}
