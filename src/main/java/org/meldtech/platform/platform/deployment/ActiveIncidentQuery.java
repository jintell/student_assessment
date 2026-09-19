package org.meldtech.platform.platform.deployment;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface ActiveIncidentQuery {

    Mono<Boolean> isActive(String incidentReference);
}
