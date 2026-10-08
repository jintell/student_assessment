package org.meldtech.platform.audit.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.Map;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.IntegerValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.identity.TenantId;

public final class AuditHashing {

    private static final byte[] CHAIN_SEED_DOMAIN =
            "meldtech.audit.chain.seed.v1\0".getBytes(StandardCharsets.UTF_8);

    private AuditHashing() {}

    public static AuditHash recordHash(AuditHash previousHash, CanonicalDocument event) {
        if (previousHash.hashAlgorithmVersion() != event.hashAlgorithmVersion()) {
            throw new IllegalArgumentException("Hash algorithm versions must match");
        }
        MessageDigest digest = sha256();
        digest.update(previousHash.bytes());
        digest.update(event.bytes());
        return new AuditHash(event.hashAlgorithmVersion(), digest.digest());
    }

    public static AuditHash chainSeed(
            TenantId tenantId,
            RetentionClass retentionClass,
            YearMonth period,
            int shardId,
            int shardCount,
            CanonicalJsonCodec codec) {
        validateShard(shardId, shardCount);
        CanonicalDocument identity =
                codec.encode(
                        new ObjectValue(
                                Map.of(
                                        "tenant_id", new StringValue(tenantId.toString()),
                                        "retention_class", new StringValue(retentionClass.name()),
                                        "period", new StringValue(period.toString()),
                                        "shard_id", new IntegerValue(shardId),
                                        "shard_count", new IntegerValue(shardCount))));
        MessageDigest digest = sha256();
        digest.update(CHAIN_SEED_DOMAIN);
        digest.update(identity.bytes());
        return new AuditHash(identity.hashAlgorithmVersion(), digest.digest());
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(
                    "The JVM does not provide mandatory SHA-256", unavailable);
        }
    }

    static void validateShard(int shardId, int shardCount) {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive");
        }
        if (shardId < 0 || shardId >= shardCount) {
            throw new IllegalArgumentException("shardId must be within the configured topology");
        }
    }
}
