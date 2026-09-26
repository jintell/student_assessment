package org.meldtech.platform.shared.api;

import org.meldtech.platform.shared.kernel.context.ActorContext;
import reactor.core.publisher.Mono;

/** Authorization policy owned by exactly one route. */
public interface SlicePolicy<R> {

    String routeId();

    Mono<PolicyDecision> evaluate(ActorContext actor, R request);
}
