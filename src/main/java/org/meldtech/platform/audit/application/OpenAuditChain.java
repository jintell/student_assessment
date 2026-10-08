package org.meldtech.platform.audit.application;

import java.util.List;
import java.util.Objects;
import org.meldtech.platform.audit.domain.AuditChainKey;
import org.meldtech.platform.audit.domain.AuditHash;

public record OpenAuditChain(
        AuditChainKey key,
        AuditHash seed,
        long committedSequence,
        AuditHash committedHead,
        List<AuditCheckpointAnchor> checkpoints) {

    public OpenAuditChain {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(seed, "seed");
        if (committedSequence < 0) {
            throw new IllegalArgumentException("committedSequence must not be negative");
        }
        Objects.requireNonNull(committedHead, "committedHead");
        checkpoints = List.copyOf(checkpoints);
    }
}
