package org.meldtech.platform.audit.domain;

import java.util.List;
import java.util.Objects;
import org.meldtech.platform.shared.kernel.identity.TenantId;

public record EpochSealMaterial(
        TenantId tenantId,
        EpochIdentity epoch,
        int shardCount,
        short hashAlgorithmVersion,
        AuditHash previousRootHash,
        long rootSequence,
        List<ShardSealMaterial> shards) {

    public EpochSealMaterial {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(previousRootHash, "previousRootHash");
        Objects.requireNonNull(shards, "shards");
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive");
        }
        if (hashAlgorithmVersion <= 0) {
            throw new IllegalArgumentException("hashAlgorithmVersion must be positive");
        }
        if (previousRootHash.hashAlgorithmVersion() != hashAlgorithmVersion) {
            throw new IllegalArgumentException("Previous root hash version must match the epoch");
        }
        if (rootSequence <= 0) {
            throw new IllegalArgumentException("rootSequence must be positive");
        }
        shards = List.copyOf(shards);
    }
}
