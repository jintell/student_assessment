package org.meldtech.platform.audit.application;

import java.util.Objects;
import reactor.core.publisher.Mono;

public final class AuditPostRestoreVerificationHook implements PostRestoreAuditVerification {

    private final AuditFullVerifier verifier;

    public AuditPostRestoreVerificationHook(AuditFullVerifier verifier) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    @Override
    public Mono<Void> verifyBeforeProductionWrites() {
        return verifier.verify(AuditFullVerifier.Trigger.POST_RESTORE).then();
    }
}
