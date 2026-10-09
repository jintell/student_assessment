package org.meldtech.platform.audit.slice.getComplianceAuditEvents.infra;

import io.r2dbc.spi.Row;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.meldtech.platform.audit.slice.getComplianceAuditEvents.Queries.AuditEventItem;

final class ComplianceAuditQuerySupport {

    private ComplianceAuditQuerySupport() {}

    static String bind(List<Object> bindings, Object value) {
        bindings.add(value);
        return "$" + bindings.size();
    }

    static AuditEventItem map(Row row) {
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
