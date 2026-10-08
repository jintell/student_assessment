package org.meldtech.platform.audit.application;

import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;

public interface AuditEvidencePreservation {

    Mono<Void> preserve(AuditVerificationFinding finding);

    Mono<Void> recordHighSeverityMetric(AuditVerificationFinding finding);

    Mono<Void> raiseP1Alert(AuditVerificationFinding finding);

    Mono<Void> haltSealingAndDisposition(TenantId tenantId);
}
