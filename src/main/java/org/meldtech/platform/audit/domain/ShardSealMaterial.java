package org.meldtech.platform.audit.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public record ShardSealMaterial(
        int shardId,
        long recordCount,
        OptionalLong sequenceStart,
        OptionalLong sequenceEnd,
        Optional<AuditHash> terminalHead) {

    public ShardSealMaterial {
        Objects.requireNonNull(sequenceStart, "sequenceStart");
        Objects.requireNonNull(sequenceEnd, "sequenceEnd");
        Objects.requireNonNull(terminalHead, "terminalHead");
        if (recordCount < 0) {
            throw new IllegalArgumentException("recordCount must not be negative");
        }
        if (recordCount == 0) {
            if (sequenceStart.isPresent() || sequenceEnd.isPresent() || terminalHead.isPresent()) {
                throw new IllegalArgumentException("Empty shards have no sequence or stored head");
            }
        } else if (sequenceStart.orElse(-1) != 1
                || sequenceEnd.orElse(-1) != recordCount
                || terminalHead.isEmpty()) {
            throw new IllegalArgumentException("Non-empty shard sequence must be dense from one");
        }
    }

    public static ShardSealMaterial empty(int shardId) {
        return new ShardSealMaterial(
                shardId, 0, OptionalLong.empty(), OptionalLong.empty(), Optional.empty());
    }

    public static ShardSealMaterial populated(int shardId, long recordCount, AuditHash head) {
        return new ShardSealMaterial(
                shardId,
                recordCount,
                OptionalLong.of(1),
                OptionalLong.of(recordCount),
                Optional.of(head));
    }
}
