package org.meldtech.platform.shared.infra.web;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.meldtech.platform.shared.api.SlicePolicy;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

@Component
final class RoutePolicyStartupValidator implements SmartInitializingSingleton {

    private final List<RouterFunction<ServerResponse>> routes;
    private final List<SlicePolicy<?>> policies;

    RoutePolicyStartupValidator(
            List<RouterFunction<ServerResponse>> routes, List<SlicePolicy<?>> policies) {
        this.routes = List.copyOf(routes);
        this.policies = List.copyOf(policies);
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<PolicyProtectedRoute> protectedRoutes =
                routes.stream()
                        .map(RoutePolicyStartupValidator::requirePolicyProtectedRoute)
                        .toList();
        Map<String, Long> routeCounts =
                protectedRoutes.stream()
                        .map(PolicyProtectedRoute::routeId)
                        .map(routeId -> requireText(routeId, "route"))
                        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        Map<String, Long> policyCounts =
                policies.stream()
                        .map(SlicePolicy::routeId)
                        .map(routeId -> requireText(routeId, "policy"))
                        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        rejectDuplicate("route", routeCounts);
        rejectDuplicate("policy", policyCounts);

        Set<String> routeIds = routeCounts.keySet();
        Set<String> policyIds = policyCounts.keySet();
        if (!routeIds.equals(policyIds)) {
            Set<String> missingPolicies = difference(routeIds, policyIds);
            Set<String> orphanPolicies = difference(policyIds, routeIds);
            throw new IllegalStateException(
                    "Route-policy coverage invalid; missing policies: "
                            + missingPolicies
                            + ", orphan policies: "
                            + orphanPolicies);
        }
    }

    private static PolicyProtectedRoute requirePolicyProtectedRoute(
            RouterFunction<ServerResponse> route) {
        if (route instanceof PolicyProtectedRoute protectedRoute) {
            return protectedRoute;
        }
        throw new IllegalStateException(
                "Application route must implement PolicyProtectedRoute: "
                        + route.getClass().getName());
    }

    private static String requireText(String value, String kind) {
        if (value.isBlank()) {
            throw new IllegalStateException(kind + " routeId must not be blank");
        }
        return value;
    }

    private static void rejectDuplicate(String kind, Map<String, Long> counts) {
        Set<String> duplicates =
                counts.entrySet().stream()
                        .filter(entry -> entry.getValue() != 1)
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toUnmodifiableSet());
        if (!duplicates.isEmpty()) {
            throw new IllegalStateException("Duplicate " + kind + " routeIds: " + duplicates);
        }
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        return left.stream()
                .filter(value -> !right.contains(value))
                .collect(Collectors.toUnmodifiableSet());
    }
}
