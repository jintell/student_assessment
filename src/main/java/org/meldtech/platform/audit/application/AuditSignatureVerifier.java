package org.meldtech.platform.audit.application;

import org.meldtech.platform.audit.domain.AuditSignature;
import org.meldtech.platform.audit.domain.AuditSigningMessage;
import reactor.core.publisher.Mono;

@FunctionalInterface
public interface AuditSignatureVerifier {

    Mono<Boolean> verify(AuditSigningMessage message, AuditSignature signature);
}
