package org.meldtech.platform.audit.application;

import java.time.Instant;
import org.meldtech.platform.audit.domain.AuditRootHead;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.audit.domain.EpochSealMaterial;
import org.meldtech.platform.audit.domain.SignedEpochSeal;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;

public interface AuditEpochSealRepository {

    Mono<AuditRootHead> readRootHead(TenantId tenantId);

    Mono<EpochSealMaterial> loadEpochMaterial(
            TenantId tenantId, EpochIdentity epoch, AuditRootHead observedRootHead);

    Mono<Instant> trustedSigningTime();

    /** Inserts the seal and advances the observed root head in one transaction. */
    Mono<Boolean> insertSealAndCompareAndSwap(SignedEpochSeal seal, AuditRootHead observedRootHead);
}
