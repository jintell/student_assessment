package org.meldtech.platform.audit.application;

import org.meldtech.platform.audit.domain.AuditChainRecord;
import org.meldtech.platform.audit.domain.SignedEpochSeal;
import reactor.core.publisher.Flux;

public interface AuditVerificationEvidence {

    Flux<OpenAuditChain> openChains();

    Flux<AuditChainRecord> records(OpenAuditChain chain);

    Flux<SignedEpochSeal> sealedEpochs();
}
