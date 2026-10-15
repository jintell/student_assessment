package org.meldtech.platform.platform.infra.runtime;

import io.r2dbc.spi.ConnectionFactory;
import java.util.Objects;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/** Infrastructure workload selection; domain code does not select schedulers or pools. */
record RuntimeLane(Scheduler scheduler, ConnectionFactory connections) {
    RuntimeLane {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(connections, "connections");
    }

    <T> Mono<T> execute(Supplier<Mono<T>> work) {
        return Mono.defer(work).subscribeOn(scheduler);
    }
}
