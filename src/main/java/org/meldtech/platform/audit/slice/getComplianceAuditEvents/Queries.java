package org.meldtech.platform.audit.slice.getComplianceAuditEvents;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.meldtech.platform.shared.api.TenantScopedQuery;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;

public interface Queries extends TenantScopedQuery {

    Mono<List<AuditEventItem>> find(
            TenantId tenantId,
            Request request,
            Instant asOf,
            Optional<ComplianceCursor> after,
            int limit);

    record AuditEventItem(
            UUID auditEventId,
            String eventType,
            String entityType,
            String entityId,
            String actorType,
            String actorId,
            Instant occurredAt,
            String correlationId,
            String retentionClass,
            int shardId,
            long sequence) {

        public AuditEventItem {
            java.util.Objects.requireNonNull(auditEventId, "auditEventId");
            java.util.Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }
}
