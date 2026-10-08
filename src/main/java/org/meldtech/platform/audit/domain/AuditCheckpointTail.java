package org.meldtech.platform.audit.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** A stable, verified prefix that may be signed without locking the append path. */
public record AuditCheckpointTail(
        AuditChainKey chainKey,
        long lastCheckpointSeq,
        long committedHeadSeq,
        AuditHash committedHeadHash,
        Optional<Instant> oldestUncheckpointedAt,
        Optional<Instant> newestUncheckpointedAt) {

    public AuditCheckpointTail {
        Objects.requireNonNull(chainKey, "chainKey");
        if (lastCheckpointSeq < 0 || committedHeadSeq < lastCheckpointSeq) {
            throw new IllegalArgumentException("checkpoint sequence bounds are invalid");
        }
        Objects.requireNonNull(committedHeadHash, "committedHeadHash");
        Objects.requireNonNull(oldestUncheckpointedAt, "oldestUncheckpointedAt");
        Objects.requireNonNull(newestUncheckpointedAt, "newestUncheckpointedAt");
        if (committedHeadHash.hashAlgorithmVersion() <= 0) {
            throw new IllegalArgumentException("hash algorithm version must be positive");
        }
        boolean hasTail = committedHeadSeq > lastCheckpointSeq;
        if (hasTail != oldestUncheckpointedAt.isPresent()
                || hasTail != newestUncheckpointedAt.isPresent()) {
            throw new IllegalArgumentException(
                    "event time bounds must describe the uncheckpointed tail");
        }
        if (hasTail
                && newestUncheckpointedAt
                        .orElseThrow()
                        .isBefore(oldestUncheckpointedAt.orElseThrow())) {
            throw new IllegalArgumentException("event time bounds are inverted");
        }
    }

    public long recordCount() {
        return committedHeadSeq - lastCheckpointSeq;
    }

    public long seqStart() {
        if (recordCount() == 0) {
            throw new IllegalStateException("an empty tail has no sequence start");
        }
        return lastCheckpointSeq + 1;
    }
}
