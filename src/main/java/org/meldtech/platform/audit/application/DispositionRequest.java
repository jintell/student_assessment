package org.meldtech.platform.audit.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.shared.kernel.identity.TenantId;

public record DispositionRequest(
        UUID requestId,
        TenantId tenantId,
        EpochIdentity epoch,
        String policyKey,
        long policyVersion,
        Instant originalRetentionStart,
        Instant dueAt,
        String authorizationReference) {

    public DispositionRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(epoch, "epoch");
        policyKey = requireText(policyKey, "policyKey");
        if (policyVersion <= 0) {
            throw new IllegalArgumentException("policyVersion must be positive");
        }
        Objects.requireNonNull(originalRetentionStart, "originalRetentionStart");
        Objects.requireNonNull(dueAt, "dueAt");
        if (dueAt.isBefore(originalRetentionStart)) {
            throw new IllegalArgumentException("dueAt must not precede the retention start");
        }
        authorizationReference = requireText(authorizationReference, "authorizationReference");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
