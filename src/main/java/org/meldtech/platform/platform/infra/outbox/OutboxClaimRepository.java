package org.meldtech.platform.platform.infra.outbox;

import io.r2dbc.spi.Row;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;

final class OutboxClaimRepository {

    static final String CLAIM_SQL =
            """
            WITH candidates AS (
                SELECT created_at, outbox_event_id
                FROM outbox.outbox_event
                WHERE state = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP
                ORDER BY created_at, outbox_event_id
                FOR UPDATE SKIP LOCKED
                LIMIT :batchSize
            ), claimed AS (
                UPDATE outbox.outbox_event AS event
                SET state = 'CLAIMED', claimed_by = :claimedBy,
                    claim_expires_at = :claimExpiresAt
                FROM candidates
                WHERE event.created_at = candidates.created_at
                  AND event.outbox_event_id = candidates.outbox_event_id
                RETURNING event.*
            )
            SELECT * FROM claimed ORDER BY created_at, outbox_event_id
            """;

    private final DatabaseClient databaseClient;

    OutboxClaimRepository(DatabaseClient databaseClient) {
        this.databaseClient = Objects.requireNonNull(databaseClient, "databaseClient");
    }

    Flux<ClaimedOutboxEvent> claim(int batchSize, String claimedBy, Instant claimExpiresAt) {
        if (batchSize < 1 || batchSize > 1000) {
            return Flux.error(
                    new IllegalArgumentException("Relay batch size must be in [1, 1000]"));
        }
        if (Objects.requireNonNull(claimedBy, "claimedBy").isBlank()) {
            return Flux.error(new IllegalArgumentException("Relay claimant must be non-blank"));
        }
        Objects.requireNonNull(claimExpiresAt, "claimExpiresAt");
        return TransactionSynchronizationManager.forCurrentTransaction()
                .thenMany(
                        databaseClient
                                .sql(CLAIM_SQL)
                                .bind("batchSize", batchSize)
                                .bind("claimedBy", claimedBy)
                                .bind("claimExpiresAt", claimExpiresAt)
                                .map((row, metadata) -> map(row))
                                .all());
    }

    private static ClaimedOutboxEvent map(Row row) {
        Object payload = Objects.requireNonNull(row.get("payload"), "payload");
        return new ClaimedOutboxEvent(
                required(row, "outbox_event_id").toString(),
                required(row, "tenant_id").toString(),
                required(row, "aggregate_type").toString(),
                required(row, "aggregate_id").toString(),
                EventType.parse(required(row, "event_type").toString()),
                payload.toString(),
                required(row, "correlation_id").toString(),
                optional(row, "traceparent"),
                optional(row, "tracestate"),
                ((Number) required(row, "attempt_count")).intValue(),
                required(row, "claimed_by").toString(),
                row.get("occurred_at", Instant.class),
                row.get("created_at", Instant.class));
    }

    private static Object required(Row row, String name) {
        return Objects.requireNonNull(row.get(name), name);
    }

    private static Optional<String> optional(Row row, String name) {
        Object value = row.get(name);
        return value == null ? Optional.empty() : Optional.of(value.toString());
    }
}
