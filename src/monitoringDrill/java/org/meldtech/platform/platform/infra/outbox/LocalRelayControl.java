package org.meldtech.platform.platform.infra.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

@Endpoint(id = "drill")
final class LocalRelayControl {
    private final AtomicBoolean held = new AtomicBoolean(true);

    LocalRelayControl(MeterRegistry meters) {
        Gauge.builder("local.drill.relay.held", held, value -> value.get() ? 1 : 0)
                .register(meters);
    }

    boolean isHeld() {
        return held.get();
    }

    @ReadOperation
    Map<String, Boolean> status() {
        return Map.of("hold", held.get());
    }

    @WriteOperation
    void control(boolean hold) {
        held.set(hold);
    }
}
