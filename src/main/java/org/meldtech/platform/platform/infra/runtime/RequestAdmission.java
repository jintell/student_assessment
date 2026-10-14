package org.meldtech.platform.platform.infra.runtime;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Per-request Reactor Context state spanning handler invocation and response rendering. */
public final class RequestAdmission implements AutoCloseable {
    private final RouteAdmission admission;
    private RouteAdmission.@Nullable Lease lease;
    private @Nullable RuntimeRoutePolicy policy;
    private boolean closed;
    private final Sinks.One<RuntimeRoutePolicy> selectedPolicy = Sinks.one();
    private final TransactionCleanup cleanup = new TransactionCleanup();

    RequestAdmission(RouteAdmission admission) {
        this.admission = admission;
    }

    synchronized void select(RuntimeRoutePolicy selected) {
        if (closed || lease != null) {
            throw new RuntimeUnavailableException();
        }
        lease = admission.acquire(selected);
        policy = selected;
        selectedPolicy.tryEmitValue(selected);
    }

    public synchronized Optional<RuntimeRoutePolicy> policy() {
        return Optional.ofNullable(policy);
    }

    TransactionCleanup cleanup() {
        return cleanup;
    }

    Mono<Void> deadline() {
        return selectedPolicy
                .asMono()
                .flatMap(
                        selected ->
                                Mono.delay(selected.workload().requestTimeout())
                                        .then(Mono.error(new RuntimeUnavailableException())));
    }

    Mono<Void> releaseAfterCleanup() {
        return Mono.defer(cleanup::await).doOnSuccess(ignored -> close());
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (lease != null) {
            lease.close();
        }
    }
}
