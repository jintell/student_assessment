package org.meldtech.platform.audit.application;

import java.util.Objects;
import org.meldtech.platform.audit.domain.AuditVerificationMismatch;
import reactor.core.publisher.Mono;

public final class AuditIntegrityFailureHandler {

    private final AuditEvidencePreservation preservation;

    public AuditIntegrityFailureHandler(AuditEvidencePreservation preservation) {
        this.preservation = Objects.requireNonNull(preservation, "preservation");
    }

    public Mono<Void> preserveAndHalt(
            AuditVerificationFinding finding, AuditVerificationMismatch mismatch) {
        Objects.requireNonNull(finding, "finding");
        Objects.requireNonNull(mismatch, "mismatch");
        return preservation
                .preserve(finding)
                .then(preservation.recordHighSeverityMetric(finding))
                .then(preservation.raiseP1Alert(finding))
                .then(preservation.haltSealingAndDisposition(finding.tenantId()))
                .then(Mono.error(mismatch));
    }
}
