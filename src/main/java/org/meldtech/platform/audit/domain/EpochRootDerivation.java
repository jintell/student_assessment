package org.meldtech.platform.audit.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ArrayValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.IntegerValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.NullValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;

public final class EpochRootDerivation {

    private static final byte[] EMPTY_SHARD_DOMAIN =
            "meldtech.audit.empty-shard.v1\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EPOCH_ROOT_DOMAIN =
            "meldtech.audit.epoch-root.v1\0".getBytes(StandardCharsets.UTF_8);

    private final CanonicalJsonCodec codec;

    public EpochRootDerivation(CanonicalJsonCodec codec) {
        this.codec = codec;
    }

    public DerivedEpochRoot derive(EpochSealMaterial material) {
        List<ShardSealMaterial> shards = validatedShards(material);
        List<Long> counts = new ArrayList<>(material.shardCount());
        List<ShardSequenceRange> ranges = new ArrayList<>(material.shardCount());
        List<CanonicalValue> canonicalShards = new ArrayList<>(material.shardCount());

        for (ShardSealMaterial shard : shards) {
            AuditHash head =
                    shard.terminalHead().orElseGet(() -> emptyShardHash(material, shard.shardId()));
            if (head.hashAlgorithmVersion() != material.hashAlgorithmVersion()) {
                throw new IllegalArgumentException("Shard head hash version must match the epoch");
            }
            counts.add(shard.recordCount());
            ranges.add(
                    new ShardSequenceRange(
                            shard.shardId(), shard.sequenceStart(), shard.sequenceEnd()));
            canonicalShards.add(canonicalShard(shard, head));
        }

        CanonicalDocument rootMaterial =
                codec.encode(
                        new ObjectValue(
                                Map.of(
                                        "tenant_id",
                                                new StringValue(material.tenantId().toString()),
                                        "retention_class",
                                                new StringValue(
                                                        material.epoch().retentionClass().name()),
                                        "period",
                                                new StringValue(
                                                        material.epoch().period().toString()),
                                        "hash_algo_version",
                                                new IntegerValue(material.hashAlgorithmVersion()),
                                        "shard_count", new IntegerValue(material.shardCount()),
                                        "shards", new ArrayValue(canonicalShards))));
        MessageDigest digest = AuditHashing.sha256();
        digest.update(EPOCH_ROOT_DOMAIN);
        digest.update(material.previousRootHash().bytes());
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(material.rootSequence()).array());
        digest.update(rootMaterial.bytes());
        return new DerivedEpochRoot(
                new AuditHash(material.hashAlgorithmVersion(), digest.digest()), counts, ranges);
    }

    private AuditHash emptyShardHash(EpochSealMaterial material, int shardId) {
        CanonicalDocument identity =
                codec.encode(
                        new ObjectValue(
                                Map.of(
                                        "tenant_id",
                                                new StringValue(material.tenantId().toString()),
                                        "retention_class",
                                                new StringValue(
                                                        material.epoch().retentionClass().name()),
                                        "period",
                                                new StringValue(
                                                        material.epoch().period().toString()),
                                        "shard_id", new IntegerValue(shardId),
                                        "shard_count", new IntegerValue(material.shardCount()))));
        MessageDigest digest = AuditHashing.sha256();
        digest.update(EMPTY_SHARD_DOMAIN);
        digest.update(identity.bytes());
        return new AuditHash(material.hashAlgorithmVersion(), digest.digest());
    }

    private static ObjectValue canonicalShard(ShardSealMaterial shard, AuditHash head) {
        return new ObjectValue(
                Map.of(
                        "shard_id", new IntegerValue(shard.shardId()),
                        "record_count", new IntegerValue(shard.recordCount()),
                        "seq_start",
                                shard.sequenceStart().isPresent()
                                        ? new IntegerValue(shard.sequenceStart().orElseThrow())
                                        : NullValue.INSTANCE,
                        "seq_end",
                                shard.sequenceEnd().isPresent()
                                        ? new IntegerValue(shard.sequenceEnd().orElseThrow())
                                        : NullValue.INSTANCE,
                        "head_hash", new StringValue(head.hex())));
    }

    private static List<ShardSealMaterial> validatedShards(EpochSealMaterial material) {
        if (material.shards().size() != material.shardCount()) {
            throw new IllegalArgumentException("Epoch material must contain every shard");
        }
        Set<Integer> identifiers = new HashSet<>();
        for (ShardSealMaterial shard : material.shards()) {
            AuditHashing.validateShard(shard.shardId(), material.shardCount());
            if (!identifiers.add(shard.shardId())) {
                throw new IllegalArgumentException("Epoch material contains a duplicate shard");
            }
        }
        return material.shards().stream()
                .sorted(Comparator.comparingInt(ShardSealMaterial::shardId))
                .toList();
    }
}
