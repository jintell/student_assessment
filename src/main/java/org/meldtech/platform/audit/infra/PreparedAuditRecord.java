package org.meldtech.platform.audit.infra;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.meldtech.platform.audit.domain.AuditChainKey;
import org.meldtech.platform.audit.domain.AuditHash;
import org.meldtech.platform.audit.domain.ResolvedRetention;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.context.ActorContext;

record PreparedAuditRecord(
        UUID auditEventId,
        AuditEvent event,
        ActorContext actor,
        Instant occurredAt,
        ResolvedRetention retention,
        AuditChainKey chain,
        long sequence,
        AuditHash previousHash,
        AuditHash recordHash,
        String payloadJson) {

    PreparedAuditRecord {
        Objects.requireNonNull(auditEventId, "auditEventId");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(retention, "retention");
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(previousHash, "previousHash");
        Objects.requireNonNull(recordHash, "recordHash");
        Objects.requireNonNull(payloadJson, "payloadJson");
        if (sequence <= 0) {
            throw new IllegalArgumentException("Audit record sequence must be positive");
        }
        if (actor.tenantId().isEmpty()
                || !actor.tenantId().orElseThrow().equals(chain.tenantId())) {
            throw new IllegalArgumentException("Audit actor tenant must match the chain tenant");
        }
        if (retention.retentionClass() != chain.epoch().retentionClass()) {
            throw new IllegalArgumentException("Resolved retention must match the chain epoch");
        }
    }
}
