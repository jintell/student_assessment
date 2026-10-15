package org.meldtech.platform.platform.infra.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import org.meldtech.platform.platform.api.SweepLock;
import org.meldtech.platform.platform.infra.runtime.RuntimeTickSources;
import reactor.core.publisher.Mono;

/** Relay tick ownership uses the shared registry and releases on every terminal path. */
final class RelaySingleton {

    private final SweepLock locks;
    private final OutboxTelemetry telemetry;
    private final Clock clock;

    RelaySingleton(SweepLock locks, OutboxTelemetry telemetry, Clock clock) {
        this.locks = Objects.requireNonNull(locks, "locks");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Mono<Boolean> runIfLeader(String sweepName, Supplier<Mono<Void>> tick) {
        Objects.requireNonNull(tick, "tick");
        return Mono.defer(
                () -> {
                    Instant startedAt = Instant.now(clock);
                    return Mono.usingWhen(
                                    locks.tryAcquire(sweepName),
                                    handle ->
                                            RuntimeTickSources.whileHeld(
                                                    handle, Mono.defer(tick).thenReturn(true)),
                                    SweepLock.Handle::close,
                                    (handle, failure) -> handle.close(),
                                    SweepLock.Handle::close)
                            .defaultIfEmpty(false)
                            .doFinally(
                                    ignored ->
                                            telemetry.relayTick(
                                                    Duration.between(
                                                            startedAt, Instant.now(clock))));
                });
    }
}
