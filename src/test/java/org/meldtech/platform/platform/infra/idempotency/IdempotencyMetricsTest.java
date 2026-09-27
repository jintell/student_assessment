package org.meldtech.platform.platform.infra.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.idempotency.ReservationOutcome;
import org.meldtech.platform.shared.kernel.idempotency.ReservationToken;
import org.meldtech.platform.shared.kernel.idempotency.StoredResponse;

class IdempotencyMetricsTest {

    @Test
    void recordsEveryBoundedOutcomeAndStoreUnavailability() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IdempotencyMetrics metrics = new IdempotencyMetrics(registry);

        metrics.record(new ReservationOutcome.Reserved(new ReservationToken("reservation")));
        metrics.record(
                new ReservationOutcome.Replay(
                        new StoredResponse(204, Map.of(), "application/json", new byte[0])));
        metrics.record(ReservationOutcome.Unavailable.INSTANCE);

        assertOutcome(registry, "reserved");
        assertOutcome(registry, "replay");
        assertOutcome(registry, "unavailable");
        assertThat(registry.get("idempotency_store_unavailable_total").counter().count())
                .isEqualTo(1.0);
    }

    private static void assertOutcome(SimpleMeterRegistry registry, String outcome) {
        assertThat(
                        registry.get("idempotency_replay_total")
                                .tag("outcome", outcome)
                                .counter()
                                .count())
                .isEqualTo(1.0);
    }
}
