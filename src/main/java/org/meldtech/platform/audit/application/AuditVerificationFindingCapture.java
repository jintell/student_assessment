package org.meldtech.platform.audit.application;

import java.util.List;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;

/** Captures the immutable snapshot metadata needed to preserve a verification failure. */
public interface AuditVerificationFindingCapture {

    Mono<AuditVerificationFinding> capture(
            TenantId tenantId, String category, List<String> affectedIdentities);
}
