package org.meldtech.platform.platform.infra.idempotency;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.meldtech.platform.shared.kernel.idempotency.ReservationOutcome;
import org.springframework.stereotype.Component;

@Component
final class IdempotencyMetrics {

    private static final String OUTCOME_METRIC = "idempotency_replay_total";
    private static final String UNAVAILABLE_METRIC = "idempotency_store_unavailable_total";

    private final Counter reserved;
    private final Counter replay;
    private final Counter unavailableOutcome;
    private final Counter storeUnavailable;

    IdempotencyMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        reserved = outcomeCounter(registry, "reserved");
        replay = outcomeCounter(registry, "replay");
        unavailableOutcome = outcomeCounter(registry, "unavailable");
        storeUnavailable = registry.counter(UNAVAILABLE_METRIC);
    }

    void record(ReservationOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        try {
            if (outcome instanceof ReservationOutcome.Reserved) {
                reserved.increment();
            } else if (outcome instanceof ReservationOutcome.Replay) {
                replay.increment();
            } else {
                unavailableOutcome.increment();
                storeUnavailable.increment();
            }
        } catch (RuntimeException ignored) {
            // Telemetry cannot change idempotency behavior.
        }
    }

    private static Counter outcomeCounter(MeterRegistry registry, String outcome) {
        return Counter.builder(OUTCOME_METRIC).tag("outcome", outcome).register(registry);
    }
}
