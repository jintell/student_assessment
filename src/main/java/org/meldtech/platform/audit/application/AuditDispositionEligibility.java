package org.meldtech.platform.audit.application;

import reactor.core.publisher.Mono;

/** FEAT-PRIV-001 boundary for the final policy and legal-hold recheck. */
@FunctionalInterface
public interface AuditDispositionEligibility {

    Mono<Boolean> remainsEligible(DispositionRequest request);
}
