package org.meldtech.platform.audit.application;

import org.meldtech.platform.audit.domain.AuditSignature;
import org.meldtech.platform.audit.domain.AuditSigningMessage;
import reactor.core.publisher.Mono;

public interface AuditEvidenceSigner {

    Mono<AuditSignature> sign(AuditSigningMessage message);
}
