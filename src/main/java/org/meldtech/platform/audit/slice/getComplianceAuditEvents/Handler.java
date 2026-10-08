package org.meldtech.platform.audit.slice.getComplianceAuditEvents;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.meldtech.platform.audit.application.PrivilegedReadEventType;
import org.meldtech.platform.shared.kernel.audit.AuditEmitter;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.BooleanValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.InstantValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.IntegerValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;
import org.meldtech.platform.shared.kernel.audit.EntityRef;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

public class Handler {

    private final Queries queries;
    private final AuditCursorCodec cursors;
    private final AuditQueryCatalogue catalogue;
    private final Clock clock;
    private final AuditEmitter auditEmitter;

    public Handler(
            Queries queries,
            AuditCursorCodec cursors,
            AuditQueryCatalogue catalogue,
            Clock clock,
            AuditEmitter auditEmitter) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.auditEmitter = Objects.requireNonNull(auditEmitter, "auditEmitter");
    }

    @Transactional
    public Mono<Response> handle(ActorContext actor, Request request) {
        TenantId tenantId =
                actor.tenantId()
                        .orElseThrow(() -> new IllegalStateException("tenant context is required"));
        if (!catalogue.isRegistered(request)) {
            return Mono.error(new IllegalArgumentException("query filter is not registered"));
        }
        Optional<ComplianceCursor> cursor = request.cursor().map(cursors::decode);
        Instant asOf = cursor.map(ComplianceCursor::asOf).orElseGet(clock::instant);
        cursor.ifPresent(value -> validateCursor(value, tenantId, request));
        return queries.find(tenantId, request, asOf, cursor, request.pageSize() + 1)
                .map(items -> response(tenantId, request, asOf, items))
                .flatMap(
                        response ->
                                Mono.from(
                                                auditEmitter.emit(
                                                        readEvent(
                                                                actor, tenantId, request, asOf,
                                                                response),
                                                        actor,
                                                        clock.instant()))
                                        .thenReturn(response));
    }

    private Response response(
            TenantId tenantId, Request request, Instant asOf, List<Queries.AuditEventItem> rows) {
        boolean hasMore = rows.size() > request.pageSize();
        List<Queries.AuditEventItem> items =
                List.copyOf(rows.subList(0, Math.min(rows.size(), request.pageSize())));
        String nextCursor = "";
        if (hasMore) {
            Queries.AuditEventItem last = items.getLast();
            nextCursor =
                    cursors.encode(
                            new ComplianceCursor(
                                    ComplianceCursor.CURRENT_SCHEMA_VERSION,
                                    tenantId.toString(),
                                    request.filterFingerprint(),
                                    asOf,
                                    last.occurredAt(),
                                    last.retentionClass(),
                                    last.shardId(),
                                    last.sequence()));
        }
        return new Response(items, nextCursor, hasMore);
    }

    private static void validateCursor(
            ComplianceCursor cursor, TenantId tenantId, Request request) {
        if (!cursor.tenantId().equals(tenantId.toString())
                || !cursor.filterFingerprint().equals(request.filterFingerprint())) {
            throw new IllegalArgumentException("cursor does not belong to this tenant and filter");
        }
    }

    private static AuditEvent readEvent(
            ActorContext actor,
            TenantId tenantId,
            Request request,
            Instant asOf,
            Response response) {
        ObjectValue payload =
                new ObjectValue(
                        java.util.Map.of(
                                "actor_id", new StringValue(actor.actorId().toString()),
                                "tenant_id", new StringValue(tenantId.toString()),
                                "correlation_id", new StringValue(actor.correlationId().toString()),
                                "query_mode", new StringValue(request.mode().name()),
                                "filter_digest", new StringValue(request.filterFingerprint()),
                                "as_of", new InstantValue(asOf),
                                "result_count", new IntegerValue(response.items().size()),
                                "has_more", new BooleanValue(response.hasMore())));
        return new AuditEvent(
                PrivilegedReadEventType.COMPLIANCE_AUDIT_EVENTS.eventType(),
                new EntityRef("audit.compliancequery", actor.correlationId().toString()),
                Set.of(RetentionClass.GENERAL_AUDIT_EVENT),
                payload);
    }
}
