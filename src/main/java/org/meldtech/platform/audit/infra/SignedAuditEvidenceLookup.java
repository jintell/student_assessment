package org.meldtech.platform.audit.infra;

import org.meldtech.platform.audit.domain.AuditEvidenceKind;
import reactor.core.publisher.Mono;

@FunctionalInterface
interface SignedAuditEvidenceLookup {

    Mono<Boolean> isAlreadySigned(AuditEvidenceKind kind, String evidenceId);
}
