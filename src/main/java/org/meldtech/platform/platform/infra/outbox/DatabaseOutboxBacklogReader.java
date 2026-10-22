package org.meldtech.platform.platform.infra.outbox;

import io.r2dbc.spi.Row;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.meldtech.platform.platform.api.SweepConnection;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

final class DatabaseOutboxBacklogReader {

    static final String SNAPSHOT_SQL =
            """
            SELECT count(*) FILTER (WHERE state = 'PENDING') AS pending,
                   count(*) FILTER (WHERE state = 'CLAIMED') AS claimed,
                   count(*) FILTER (WHERE state = 'PUBLISHED') AS published,
                   count(*) FILTER (WHERE state = 'FAILED') AS failed,
                   min(created_at) FILTER (WHERE state = 'PENDING') AS oldest_pending,
                   statement_timestamp() AS observed_at
            FROM outbox.outbox_event
            """;

    Mono<OutboxBacklogSnapshot> read(SweepConnection connection, Duration timeout) {
        Mono<Void> limit =
                Flux.from(
                                connection
                                        .createStatement(
                                                "SELECT set_config('statement_timeout', $1, true)")
                                        .bind(0, timeout.toMillis() + "ms")
                                        .execute())
                        .concatMap(
                                result -> result.map((row, metadata) -> row.get(0, String.class)))
                        .then();
        return limit.then(
                Flux.defer(() -> Flux.from(connection.createStatement(SNAPSHOT_SQL).execute()))
                        .concatMap(result -> result.map((row, metadata) -> snapshot(row)))
                        .single());
    }

    private static OutboxBacklogSnapshot snapshot(Row row) {
        Instant observedAt = required(row, "observed_at", Instant.class);
        Instant oldest = row.get("oldest_pending", Instant.class);
        Duration age =
                oldest == null || oldest.isAfter(observedAt)
                        ? Duration.ZERO
                        : Duration.between(oldest, observedAt);
        return new OutboxBacklogSnapshot(
                required(row, "pending", Long.class),
                required(row, "claimed", Long.class),
                required(row, "published", Long.class),
                required(row, "failed", Long.class),
                age,
                observedAt);
    }

    private static <T> T required(Row row, String column, Class<T> type) {
        return Objects.requireNonNull(row.get(column, type), column);
    }
}
