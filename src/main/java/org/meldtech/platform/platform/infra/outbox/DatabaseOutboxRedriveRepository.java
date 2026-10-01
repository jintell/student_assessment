package org.meldtech.platform.platform.infra.outbox;

import java.util.Objects;
import java.util.UUID;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

final class DatabaseOutboxRedriveRepository implements OutboxRedriveRepository {

    static final String REQUEUE_SQL =
            """
            UPDATE outbox.outbox_event
            SET state = 'PENDING', attempt_count = 0,
                next_attempt_at = CURRENT_TIMESTAMP,
                claim_expires_at = NULL, claimed_by = NULL,
                published_at = NULL, last_error = NULL
            WHERE tenant_id = :tenantId AND outbox_event_id = :eventId
              AND state = 'FAILED'
            """;

    private final DatabaseClient databaseClient;

    DatabaseOutboxRedriveRepository(DatabaseClient databaseClient) {
        this.databaseClient = Objects.requireNonNull(databaseClient, "databaseClient");
    }

    @Override
    public Mono<Boolean> requeueFailed(TenantId tenantId, UUID eventId) {
        return databaseClient
                .sql(REQUEUE_SQL)
                .bind("tenantId", tenantId.toString())
                .bind("eventId", eventId)
                .fetch()
                .rowsUpdated()
                .map(rows -> rows == 1);
    }
}
