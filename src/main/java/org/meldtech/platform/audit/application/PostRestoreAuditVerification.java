package org.meldtech.platform.audit.application;

import reactor.core.publisher.Mono;

/** Invocation boundary handed to FEAT-OPS-003 before production writes are enabled. */
@FunctionalInterface
public interface PostRestoreAuditVerification {

    Mono<Void> verifyBeforeProductionWrites();
}
