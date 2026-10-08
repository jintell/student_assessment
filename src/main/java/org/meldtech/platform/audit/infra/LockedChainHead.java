package org.meldtech.platform.audit.infra;

import org.meldtech.platform.audit.domain.AuditHash;

record LockedChainHead(long sequence, AuditHash headHash) {

    LockedChainHead {
        if (sequence < 0) {
            throw new IllegalArgumentException("Chain-head sequence must not be negative");
        }
    }
}
