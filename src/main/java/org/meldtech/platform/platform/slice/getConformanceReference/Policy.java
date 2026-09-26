package org.meldtech.platform.platform.slice.getConformanceReference;

import org.meldtech.platform.shared.api.PolicyDecision;
import org.meldtech.platform.shared.api.SlicePolicy;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
final class Policy implements SlicePolicy<Request> {

    static final String REQUIRED_AUTHORITY = "platform:conformance:read";

    @Override
    public String routeId() {
        return Endpoint.ROUTE_ID;
    }

    @Override
    public Mono<PolicyDecision> evaluate(ActorContext actor, Request request) {
        return Mono.just(PolicyDecision.DENY);
    }
}
