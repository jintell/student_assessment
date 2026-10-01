package org.meldtech.platform.platform.infra.outbox;

import java.time.Duration;
import java.util.Objects;

record RelayConfiguration(int batchSize, Duration tickInterval) {

    static final int DEFAULT_BATCH_SIZE = 200;
    static final Duration DEFAULT_TICK_INTERVAL = Duration.ofMillis(200);

    RelayConfiguration {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Relay batch size must be in [1, 1000]");
        }
        Objects.requireNonNull(tickInterval, "tickInterval");
        if (tickInterval.compareTo(Duration.ofMillis(50)) < 0
                || tickInterval.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("Relay tick interval must be in [50ms, 5000ms]");
        }
    }

    static RelayConfiguration defaults() {
        return new RelayConfiguration(DEFAULT_BATCH_SIZE, DEFAULT_TICK_INTERVAL);
    }
}
