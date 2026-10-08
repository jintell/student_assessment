package org.meldtech.platform.audit.slice.getComplianceAuditEvents;

import java.util.List;
import java.util.Objects;

public record Response(List<Queries.AuditEventItem> items, String nextCursor, boolean hasMore) {

    public Response {
        items = List.copyOf(items);
        Objects.requireNonNull(nextCursor, "nextCursor");
        if (hasMore != !nextCursor.isEmpty()) {
            throw new IllegalArgumentException(
                    "nextCursor must be present exactly when hasMore is true");
        }
    }
}
