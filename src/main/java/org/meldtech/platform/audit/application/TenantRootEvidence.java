package org.meldtech.platform.audit.application;

import java.util.List;
import java.util.Objects;
import org.meldtech.platform.audit.domain.AuditRootHead;
import org.meldtech.platform.audit.domain.SignedEpochSeal;
import org.meldtech.platform.shared.kernel.identity.TenantId;

public record TenantRootEvidence(
        TenantId tenantId, List<SignedEpochSeal> seals, AuditRootHead currentHead) {

    public TenantRootEvidence {
        Objects.requireNonNull(tenantId, "tenantId");
        seals = List.copyOf(seals);
        Objects.requireNonNull(currentHead, "currentHead");
    }
}
