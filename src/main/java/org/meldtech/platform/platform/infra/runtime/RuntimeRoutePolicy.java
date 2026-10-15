package org.meldtech.platform.platform.infra.runtime;

import java.time.Duration;
import java.util.Objects;

/** Reviewed route identity and workload classification, never supplied by a client. */
public record RuntimeRoutePolicy(String id, String method, String path, Workload workload) {
    public RuntimeRoutePolicy {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(workload, "workload");
        if (id.isBlank() || !path.startsWith("/") || !method.matches("[A-Z]+")) {
            throw new IllegalArgumentException("Invalid runtime route policy");
        }
    }

    public enum Workload {
        EXAM(32, 64),
        ADMINISTRATIVE(8, 12),
        REPORT(2, 2),
        PIN_DISTRIBUTION(5, 5);

        private final int routeLimit;
        private final int classLimit;

        Workload(int routeLimit, int classLimit) {
            this.routeLimit = routeLimit;
            this.classLimit = classLimit;
        }

        int routeLimit() {
            return routeLimit;
        }

        int classLimit() {
            return classLimit;
        }

        boolean sharesGeneralCapacity() {
            return this == ADMINISTRATIVE || this == REPORT;
        }

        public Duration statementTimeout() {
            return switch (this) {
                case EXAM -> Duration.ofMillis(500);
                case ADMINISTRATIVE, PIN_DISTRIBUTION -> Duration.ofSeconds(1);
                case REPORT -> Duration.ofSeconds(2);
            };
        }

        Duration requestTimeout() {
            return switch (this) {
                case EXAM -> Duration.ofSeconds(2);
                case ADMINISTRATIVE, PIN_DISTRIBUTION -> Duration.ofSeconds(3);
                case REPORT -> Duration.ofSeconds(4);
            };
        }
    }
}
