package org.meldtech.platform.shared.api;

import reactor.core.publisher.Mono;

/** Authorization policy owned by exactly one route. */
public interface SlicePolicy<R> {

    String routeId();

    Mono<PolicyDecision> evaluate(RequestCarrier carrier, R request);
}
