package org.meldtech.platform.audit.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.InstantValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.IntegerValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;

public record UnsignedAuditCheckpoint(AuditCheckpointTail tail, Instant signedAt) {

    public UnsignedAuditCheckpoint {
        Objects.requireNonNull(tail, "tail");
        Objects.requireNonNull(signedAt, "signedAt");
        if (tail.recordCount() == 0) {
            throw new IllegalArgumentException("an empty chain does not need a checkpoint");
        }
    }

    public String evidenceId() {
        AuditChainKey key = tail.chainKey();
        return String.join(
                ":",
                key.tenantId().toString(),
                key.epoch().retentionClass().name(),
                key.epoch().period().toString(),
                Integer.toString(key.shardId()),
                Long.toString(tail.committedHeadSeq()));
    }

    public AuditSigningMessage signingMessage(CanonicalJsonCodec codec) {
        Objects.requireNonNull(codec, "codec");
        AuditChainKey key = tail.chainKey();
        Map<String, CanonicalValue> fields =
                Map.ofEntries(
                        Map.entry("tenant_id", new StringValue(key.tenantId().toString())),
                        Map.entry(
                                "retention_class",
                                new StringValue(key.epoch().retentionClass().name())),
                        Map.entry("period", new StringValue(key.epoch().period().toString())),
                        Map.entry("shard_id", new IntegerValue(key.shardId())),
                        Map.entry("shard_count", new IntegerValue(key.shardCount())),
                        Map.entry("seq_start", new IntegerValue(tail.seqStart())),
                        Map.entry("seq_end", new IntegerValue(tail.committedHeadSeq())),
                        Map.entry("record_count", new IntegerValue(tail.recordCount())),
                        Map.entry("head_hash", new StringValue(tail.committedHeadHash().hex())),
                        Map.entry(
                                "hash_algo_version",
                                new IntegerValue(tail.committedHeadHash().hashAlgorithmVersion())),
                        Map.entry(
                                "event_occurred_from",
                                new InstantValue(tail.oldestUncheckpointedAt().orElseThrow())),
                        Map.entry(
                                "event_occurred_to",
                                new InstantValue(tail.newestUncheckpointedAt().orElseThrow())),
                        Map.entry("signed_at", new InstantValue(signedAt)));
        CanonicalDocument document = codec.encode(new ObjectValue(fields));
        return AuditSigningMessage.checkpoint(
                evidenceId(), document.hashAlgorithmVersion(), document.bytes());
    }
}
