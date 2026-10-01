package org.meldtech.platform.platform.infra.outbox;

import java.util.Objects;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;

final class StaleClaimReclaimer {

    static final String RECLAIM_SQL =
            """
            UPDATE outbox.outbox_event
            SET state = 'PENDING', claim_expires_at = NULL, claimed_by = NULL,
                next_attempt_at = CURRENT_TIMESTAMP
            WHERE state = 'CLAIMED' AND claim_expires_at < CURRENT_TIMESTAMP
            """;

    private final DatabaseClient databaseClient;
    private final OutboxTelemetry telemetry;

    StaleClaimReclaimer(DatabaseClient databaseClient, OutboxTelemetry telemetry) {
        this.databaseClient = Objects.requireNonNull(databaseClient, "databaseClient");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    Mono<Long> reclaim() {
        return TransactionSynchronizationManager.forCurrentTransaction()
                .then(databaseClient.sql(RECLAIM_SQL).fetch().rowsUpdated())
                .doOnNext(telemetry::staleClaimsReclaimed);
    }
}
