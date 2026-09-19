package org.meldtech.platform.platform.deployment;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface EmergencyOverrideEvidenceQuery {

    Mono<EmergencyOverrideEvidence> find(String evidenceIdentifier);
}
