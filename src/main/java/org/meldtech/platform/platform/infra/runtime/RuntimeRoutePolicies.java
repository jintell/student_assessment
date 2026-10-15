package org.meldtech.platform.platform.infra.runtime;

import java.util.List;
import org.meldtech.platform.platform.api.RuntimeRole;
import org.meldtech.platform.platform.infra.runtime.RuntimeRoutePolicy.Workload;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;

final class RuntimeRoutePolicies {
    private final List<RuntimeRoutePolicy> policies;

    RuntimeRoutePolicies(RuntimeRole role) {
        policies =
                switch (role) {
                    case API ->
                            List.of(
                                    new RuntimeRoutePolicy(
                                            "platform.getConformanceReference",
                                            "GET",
                                            "/api/v1/platform/conformance-reference",
                                            Workload.ADMINISTRATIVE));
                    case WORKER -> List.of();
                    case PINDIST ->
                            FeatureDeliveryStatus.load().contains("FEAT-EXAM-003")
                                    ? List.of(
                                            new RuntimeRoutePolicy(
                                                    "examAccess.pinDistribution",
                                                    "GET",
                                                    "/api/v1/exam-sessions/{id}/pin-distribution",
                                                    Workload.PIN_DISTRIBUTION))
                                    : List.of();
                };
    }

    List<RuntimeRoutePolicy> all() {
        return policies;
    }

    RuntimeRoutePolicy matched(ServerWebExchange exchange) {
        Object pattern = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(pattern instanceof PathPattern path)) {
            throw new RuntimeUnavailableException();
        }
        return policies.stream()
                .filter(
                        policy ->
                                policy.method().equals(exchange.getRequest().getMethod().name())
                                        && policy.path().equals(path.getPatternString()))
                .findFirst()
                .orElseThrow(RuntimeUnavailableException::new);
    }
}
