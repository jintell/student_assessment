package org.meldtech.platform.shared.infra.web;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.meldtech.platform.shared.api.PolicyDecision;
import org.meldtech.platform.shared.api.PolicyResolver;
import org.meldtech.platform.shared.api.SlicePolicy;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
final class DefaultPolicyRegistry implements PolicyResolver {

    private final Map<String, List<SlicePolicy<?>>> policiesByRoute;

    DefaultPolicyRegistry(List<SlicePolicy<?>> policies) {
        policiesByRoute =
                policies.stream()
                        .collect(
                                Collectors.collectingAndThen(
                                        Collectors.groupingBy(SlicePolicy::routeId), Map::copyOf));
    }

    @Override
    public <R> Mono<PolicyDecision> evaluate(String routeId, ActorContext actor, R request) {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(request, "request");

        List<SlicePolicy<?>> policies = policiesByRoute.getOrDefault(routeId, List.of());
        if (policies.size() != 1) {
            return Mono.just(PolicyDecision.DENY);
        }

        return evaluate(policies.getFirst(), actor, request)
                .defaultIfEmpty(PolicyDecision.DENY)
                .onErrorReturn(PolicyDecision.DENY);
    }

    Map<String, List<SlicePolicy<?>>> policiesByRoute() {
        return policiesByRoute;
    }

    @SuppressWarnings("unchecked")
    private static <R> Mono<PolicyDecision> evaluate(
            SlicePolicy<?> policy, ActorContext actor, R request) {
        return ((SlicePolicy<R>) policy).evaluate(actor, request);
    }
}
