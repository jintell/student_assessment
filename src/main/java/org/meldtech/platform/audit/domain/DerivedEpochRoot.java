package org.meldtech.platform.audit.domain;

import java.util.List;

public record DerivedEpochRoot(
        AuditHash epochRoot, List<Long> perShardCounts, List<ShardSequenceRange> sequenceRanges) {

    public DerivedEpochRoot {
        perShardCounts = List.copyOf(perShardCounts);
        sequenceRanges = List.copyOf(sequenceRanges);
    }
}
