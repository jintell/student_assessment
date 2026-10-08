package org.meldtech.platform.audit.application;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;

public final class AuditEpochCloser {

    private final AuditEpochSealOperation sealer;

    public AuditEpochCloser(AuditEpochSealOperation sealer) {
        this.sealer = Objects.requireNonNull(sealer, "sealer");
    }

    public Mono<List<ClosedEpoch>> close(
            TenantId tenantId, Collection<EpochIdentity> eligibleEpochs) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(eligibleEpochs, "eligibleEpochs");
        return reactor.core.publisher.Flux.fromIterable(
                        eligibleEpochs.stream()
                                .distinct()
                                .sorted(EpochIdentity.CANONICAL_ORDER)
                                .toList())
                .concatMap(
                        epoch ->
                                sealer.seal(tenantId, epoch)
                                        .map(result -> new ClosedEpoch(epoch, result)))
                .collectList();
    }

    public record ClosedEpoch(EpochIdentity epoch, AuditEpochSealer.SealResult result) {

        public ClosedEpoch {
            Objects.requireNonNull(epoch, "epoch");
            Objects.requireNonNull(result, "result");
        }
    }
}
