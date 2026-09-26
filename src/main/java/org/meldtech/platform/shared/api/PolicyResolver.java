package org.meldtech.platform.shared.api;

import org.meldtech.platform.shared.kernel.context.ActorContext;
import reactor.core.publisher.Mono;

/** Resolves and evaluates the single policy registered for an application route. */
public interface PolicyResolver {

    <R> Mono<PolicyDecision> evaluate(String routeId, ActorContext actor, R request);
}
