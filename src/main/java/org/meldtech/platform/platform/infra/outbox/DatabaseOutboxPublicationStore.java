package org.meldtech.platform.platform.infra.outbox;

import java.time.Instant;
import java.util.Objects;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

final class DatabaseOutboxPublicationStore implements OutboxPublicationStore {

    private static final String MARK_PUBLISHED =
            """
            UPDATE outbox.outbox_event
            SET state = 'PUBLISHED', published_at = :publishedAt,
                claim_expires_at = NULL, claimed_by = NULL, last_error = NULL
            WHERE created_at = :createdAt AND outbox_event_id = CAST(:eventId AS uuid)
              AND state = 'CLAIMED' AND claimed_by = :claimedBy
            """;
    private static final String RECORD_FAILURE =
            """
            UPDATE outbox.outbox_event
            SET attempt_count = attempt_count + 1,
                state = CASE WHEN attempt_count + 1 >= 8 THEN 'FAILED' ELSE 'PENDING' END,
                next_attempt_at = :nextAttemptAt,
                last_error = CASE WHEN attempt_count + 1 >= 8 THEN :reason ELSE NULL END,
                claim_expires_at = NULL, claimed_by = NULL
            WHERE created_at = :createdAt AND outbox_event_id = CAST(:eventId AS uuid)
              AND state = 'CLAIMED' AND claimed_by = :claimedBy
            RETURNING state, attempt_count
            """;

    private final DatabaseClient databaseClient;

    DatabaseOutboxPublicationStore(DatabaseClient databaseClient) {
        this.databaseClient = Objects.requireNonNull(databaseClient, "databaseClient");
    }

    @Override
    public Mono<Void> markPublished(ClaimedOutboxEvent event, Instant publishedAt) {
        return databaseClient
                .sql(MARK_PUBLISHED)
                .bind("publishedAt", publishedAt)
                .bind("createdAt", event.createdAt())
                .bind("eventId", event.eventId())
                .bind("claimedBy", event.claimedBy())
                .fetch()
                .rowsUpdated()
                .flatMap(DatabaseOutboxPublicationStore::requireSingleRow);
    }

    @Override
    public Mono<FailureTransition> recordFailure(
            ClaimedOutboxEvent event, PublicationFailureReason reason, Instant nextAttemptAt) {
        return databaseClient
                .sql(RECORD_FAILURE)
                .bind("nextAttemptAt", nextAttemptAt)
                .bind("reason", reason.name())
                .bind("createdAt", event.createdAt())
                .bind("eventId", event.eventId())
                .bind("claimedBy", event.claimedBy())
                .map(
                        (row, metadata) ->
                                new FailureTransition(
                                        "FAILED".equals(row.get("state", String.class)),
                                        Objects.requireNonNull(
                                                        row.get("attempt_count", Number.class),
                                                        "attempt_count")
                                                .intValue()))
                .one()
                .switchIfEmpty(
                        Mono.error(
                                new IllegalStateException(
                                        "Outbox failure transition affected no row")));
    }

    private static Mono<Void> requireSingleRow(Long rows) {
        return rows == 1
                ? Mono.empty()
                : Mono.error(
                        new IllegalStateException(
                                "Outbox publication transition affected " + rows + " rows"));
    }
}
