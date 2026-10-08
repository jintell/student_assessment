package org.meldtech.platform.audit.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ArrayValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.InstantValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.IntegerValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;

public record UnsignedEpochSeal(
        EpochSealMaterial material, DerivedEpochRoot derivedRoot, Instant signedAt) {

    public UnsignedEpochSeal {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(derivedRoot, "derivedRoot");
        Objects.requireNonNull(signedAt, "signedAt");
        if (material.shardCount() != derivedRoot.perShardCounts().size()
                || material.shardCount() != derivedRoot.sequenceRanges().size()) {
            throw new IllegalArgumentException("derived root does not match the epoch topology");
        }
    }

    public String evidenceId() {
        return String.join(
                ":",
                material.tenantId().toString(),
                material.epoch().retentionClass().name(),
                material.epoch().period().toString());
    }

    public AuditSigningMessage signingMessage(CanonicalJsonCodec codec) {
        Objects.requireNonNull(codec, "codec");
        List<CanonicalValue> counts =
                derivedRoot.perShardCounts().stream()
                        .map(count -> (CanonicalValue) new IntegerValue(count))
                        .toList();
        List<CanonicalValue> ranges = new ArrayList<>(derivedRoot.sequenceRanges().size());
        for (ShardSequenceRange range : derivedRoot.sequenceRanges()) {
            ranges.add(
                    new ObjectValue(
                            Map.of(
                                    "shard_id", new IntegerValue(range.shardId()),
                                    "seq_start", rangeStart(range),
                                    "seq_end", rangeEnd(range))));
        }
        CanonicalDocument document =
                codec.encode(
                        new ObjectValue(
                                Map.ofEntries(
                                        Map.entry(
                                                "tenant_id",
                                                new StringValue(material.tenantId().toString())),
                                        Map.entry(
                                                "retention_class",
                                                new StringValue(
                                                        material.epoch().retentionClass().name())),
                                        Map.entry(
                                                "period",
                                                new StringValue(
                                                        material.epoch().period().toString())),
                                        Map.entry(
                                                "root_seq",
                                                new IntegerValue(material.rootSequence())),
                                        Map.entry(
                                                "previous_root_hash",
                                                new StringValue(material.previousRootHash().hex())),
                                        Map.entry(
                                                "epoch_root",
                                                new StringValue(derivedRoot.epochRoot().hex())),
                                        Map.entry(
                                                "shard_count",
                                                new IntegerValue(material.shardCount())),
                                        Map.entry("per_shard_counts", new ArrayValue(counts)),
                                        Map.entry("sequence_ranges", new ArrayValue(ranges)),
                                        Map.entry(
                                                "hash_algo_version",
                                                new IntegerValue(material.hashAlgorithmVersion())),
                                        Map.entry("signed_at", new InstantValue(signedAt)))));
        return AuditSigningMessage.epochSeal(
                evidenceId(), document.hashAlgorithmVersion(), document.bytes());
    }

    private static CanonicalValue rangeStart(ShardSequenceRange range) {
        return range.start().isPresent()
                ? new IntegerValue(range.start().orElseThrow())
                : CanonicalValue.NullValue.INSTANCE;
    }

    private static CanonicalValue rangeEnd(ShardSequenceRange range) {
        return range.end().isPresent()
                ? new IntegerValue(range.end().orElseThrow())
                : CanonicalValue.NullValue.INSTANCE;
    }
}
