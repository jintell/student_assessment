package org.meldtech.platform.audit.application;

import org.meldtech.platform.audit.domain.AuditChainRecord;
import reactor.core.publisher.Flux;

public interface AuditFullVerificationEvidence {

    Flux<OpenAuditChain> retainedChains();

    Flux<AuditChainRecord> records(OpenAuditChain chain);

    Flux<TenantRootEvidence> tenantRootChains();
}
