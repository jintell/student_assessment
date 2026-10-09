package org.meldtech.platform.audit.infra;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.meldtech.platform.audit.slice.getComplianceAuditEvents.ComplianceCursor;
import org.meldtech.platform.audit.slice.getComplianceAuditEvents.Queries;
import org.meldtech.platform.audit.slice.getComplianceAuditEvents.Request;
import org.meldtech.platform.platform.api.TransactionalConnection;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Uses the caller's secured transaction, shared with the privileged-read audit append. */
public final class R2dbcComplianceAuditQueries implements Queries {

    @Override
    public Mono<List<AuditEventItem>> find(
            TenantId tenantId,
            Request request,
            Instant asOf,
            Optional<ComplianceCursor> after,
            int limit) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(after, "after");
        if (limit < 1 || limit > Request.MAX_PAGE_SIZE + 1) {
            throw new IllegalArgumentException("query limit must be between 1 and 501");
        }
        after.ifPresent(
                cursor -> {
                    if (!cursor.tenantId().equals(tenantId.toString())
                            || !cursor.filterFingerprint().equals(request.filterFingerprint())
                            || !cursor.asOf().equals(asOf)) {
                        throw new IllegalArgumentException("cursor does not belong to this query");
                    }
                });
        return TransactionalConnection.current()
                .flatMap(
                        connection -> {
                            List<Object> bindings = new ArrayList<>();
                            StringBuilder sql =
                                    new StringBuilder(
                                            """
                    SELECT audit_event_id, event_type, entity_type, entity_id, actor_type,
                           actor_id, occurred_at, correlation_id, retention_class, shard_id, seq
                    FROM audit.audit_event
                    WHERE tenant_id = $1 AND occurred_at <= $2
                    """);
                            bindings.add(UUID.fromString(tenantId.toString()));
                            bindings.add(asOf.atOffset(ZoneOffset.UTC));
                            request.occurredFrom()
                                    .ifPresent(
                                            value ->
                                                    sql.append(" AND occurred_at >= ")
                                                            .append(
                                                                    bind(
                                                                            bindings,
                                                                            value.atOffset(
                                                                                    ZoneOffset
                                                                                            .UTC))));
                            request.occurredTo()
                                    .ifPresent(
                                            value ->
                                                    sql.append(" AND occurred_at < ")
                                                            .append(
                                                                    bind(
                                                                            bindings,
                                                                            value.atOffset(
                                                                                    ZoneOffset
                                                                                            .UTC))));
                            request.entityType()
                                    .ifPresent(
                                            value -> {
                                                sql.append(" AND entity_type = ")
                                                        .append(bind(bindings, value));
                                                sql.append(" AND entity_id = ")
                                                        .append(
                                                                bind(
                                                                        bindings,
                                                                        request.entityId()
                                                                                .orElseThrow()));
                                            });
                            request.eventType()
                                    .ifPresent(
                                            value ->
                                                    sql.append(" AND event_type = ")
                                                            .append(bind(bindings, value)));
                            after.ifPresent(
                                    cursor -> {
                                        String time =
                                                bind(
                                                        bindings,
                                                        cursor.occurredAt()
                                                                .atOffset(ZoneOffset.UTC));
                                        String retention = bind(bindings, cursor.retentionClass());
                                        String shard = bind(bindings, cursor.shardId());
                                        String sequence = bind(bindings, cursor.sequence());
                                        // Retention sorts ascending; the other cursor components
                                        // sort descending.
                                        sql.append(" AND (occurred_at < ")
                                                .append(time)
                                                .append(" OR (occurred_at = ")
                                                .append(time)
                                                .append(" AND (retention_class > ")
                                                .append(retention)
                                                .append(" OR (retention_class = ")
                                                .append(retention)
                                                .append(" AND (shard_id < ")
                                                .append(shard)
                                                .append(" OR (shard_id = ")
                                                .append(shard)
                                                .append(" AND seq < ")
                                                .append(sequence)
                                                .append("))))))");
                                    });
                            sql.append(
                                            " ORDER BY occurred_at DESC, retention_class ASC,"
                                                    + " shard_id DESC, seq DESC LIMIT ")
                                    .append(bind(bindings, limit));
                            Statement statement = connection.createStatement(sql.toString());
                            for (int index = 0; index < bindings.size(); index++) {
                                statement.bind(index, bindings.get(index));
                            }
                            return Flux.from(statement.execute())
                                    .concatMap(result -> result.map((row, metadata) -> map(row)))
                                    .collectList();
                        });
    }

    private static String bind(List<Object> bindings, Object value) {
        bindings.add(value);
        return "$" + bindings.size();
    }

    private static AuditEventItem map(Row row) {
        return new AuditEventItem(
                required(row, "audit_event_id", UUID.class),
                required(row, "event_type", String.class),
                required(row, "entity_type", String.class),
                required(row, "entity_id", String.class),
                required(row, "actor_type", String.class),
                required(row, "actor_id", String.class),
                required(row, "occurred_at", OffsetDateTime.class).toInstant(),
                required(row, "correlation_id", String.class),
                required(row, "retention_class", String.class),
                required(row, "shard_id", Integer.class),
                required(row, "seq", Long.class));
    }

    private static <T> T required(Row row, String column, Class<T> type) {
        return Objects.requireNonNull(row.get(column, type), column);
    }
}
