package org.meldtech.platform.audit.application;

import java.util.Objects;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;

public final class AuditEpochSealingJob {

    static final String SEAL_UNAVAILABLE = "AUDIT_SEAL_UNAVAILABLE";

    private final AuditEpochSealOperation sealer;
    private final AuditSealAlertSink alerts;

    public AuditEpochSealingJob(AuditEpochSealOperation sealer, AuditSealAlertSink alerts) {
        this.sealer = Objects.requireNonNull(sealer, "sealer");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
    }

    public Mono<JobResult> run(TenantId tenantId, EpochIdentity epoch) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(epoch, "epoch");
        return sealer.seal(tenantId, epoch)
                .map(
                        result ->
                                result == AuditEpochSealer.SealResult.SEALED
                                        ? JobResult.SEALED
                                        : defer(tenantId, epoch))
                .onErrorResume(ignored -> Mono.just(defer(tenantId, epoch)));
    }

    private JobResult defer(TenantId tenantId, EpochIdentity epoch) {
        alerts.sealDeferred(tenantId, epoch, SEAL_UNAVAILABLE);
        return JobResult.DEFERRED;
    }

    public enum JobResult {
        SEALED,
        DEFERRED
    }
}
