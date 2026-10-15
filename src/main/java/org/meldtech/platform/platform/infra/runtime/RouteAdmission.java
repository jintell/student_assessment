package org.meldtech.platform.platform.infra.runtime;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.meldtech.platform.platform.infra.runtime.RuntimeRoutePolicy.Workload;

/** A bounded set of counters; capacity acquisition never waits or queues a request. */
final class RouteAdmission {
    private final Map<String, Integer> routes = new HashMap<>();
    private final Map<Workload, Integer> classes = new EnumMap<>(Workload.class);
    private int general;

    RouteAdmission(List<RuntimeRoutePolicy> policies) {
        for (var policy : policies) {
            if (routes.putIfAbsent(policy.id(), 0) != null) {
                throw new IllegalArgumentException("Duplicate runtime route policy");
            }
        }
    }

    synchronized Lease acquire(RuntimeRoutePolicy policy) {
        Integer route = routes.get(policy.id());
        Workload workload = policy.workload();
        int count = classes.getOrDefault(workload, 0);
        if (route == null
                || route >= workload.routeLimit()
                || count >= workload.classLimit()
                || (workload.sharesGeneralCapacity() && general >= 12)) {
            throw new RuntimeUnavailableException();
        }
        routes.put(policy.id(), route + 1);
        classes.put(workload, count + 1);
        if (workload.sharesGeneralCapacity()) {
            general++;
        }
        return new Lease(policy);
    }

    final class Lease implements AutoCloseable {
        private final RuntimeRoutePolicy policy;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(RuntimeRoutePolicy policy) {
            this.policy = policy;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                synchronized (RouteAdmission.this) {
                    routes.computeIfPresent(policy.id(), (key, count) -> count - 1);
                    classes.computeIfPresent(policy.workload(), (key, count) -> count - 1);
                    if (policy.workload().sharesGeneralCapacity()) {
                        general--;
                    }
                }
            }
        }
    }
}
