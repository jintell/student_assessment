package org.meldtech.platform.platform.deployment;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface EmergencyOverrideAuditTrail {

    Mono<Void> record(EmergencyOverrideAuditEvent event);
}
