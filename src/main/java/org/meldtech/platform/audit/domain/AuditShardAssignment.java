package org.meldtech.platform.audit.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import org.meldtech.platform.shared.kernel.audit.EntityRef;

public final class AuditShardAssignment {

    private static final byte[] DOMAIN =
            "meldtech.audit.shard.v1\0".getBytes(StandardCharsets.UTF_8);

    private AuditShardAssignment() {}

    public static int shardFor(EntityRef entity, int shardCount) {
        Objects.requireNonNull(entity, "entity");
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive");
        }
        MessageDigest digest = AuditHashing.sha256();
        digest.update(DOMAIN);
        digest.update(entity.entityType().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        byte[] hash = digest.digest(entity.entityId().getBytes(StandardCharsets.UTF_8));

        long remainder = 0;
        for (int index = 0; index < Long.BYTES; index++) {
            remainder = (remainder * 256 + Byte.toUnsignedInt(hash[index])) % shardCount;
        }
        return Math.toIntExact(remainder);
    }
}
