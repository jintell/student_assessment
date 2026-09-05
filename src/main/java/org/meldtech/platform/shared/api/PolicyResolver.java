package org.meldtech.platform.shared.api;

import reactor.core.publisher.Mono;

/** Resolves and evaluates the single policy registered for an application route. */
public interface PolicyResolver {

    <R> Mono<PolicyDecision> evaluate(String routeId, RequestCarrier carrier, R request);
}
