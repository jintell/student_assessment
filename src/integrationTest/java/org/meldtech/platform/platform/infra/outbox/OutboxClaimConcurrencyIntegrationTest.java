package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class OutboxClaimConcurrencyIntegrationTest extends OutboxPostgreSqlIntegrationTestSupport {

    private static final int RELAY_COUNT = 4;
    private static final int BATCH_SIZE = 15;

    @Test
    void concurrentRelaysClaimDisjointBatchesWithoutWaitingForLocks() throws Exception {
        Set<String> expected = seedPendingEvents(RELAY_COUNT * BATCH_SIZE);
        CyclicBarrier claimsHeldOpen = new CyclicBarrier(RELAY_COUNT);

        List<Mono<List<ClaimedOutboxEvent>>> relays =
                java.util.stream.IntStream.range(0, RELAY_COUNT)
                        .mapToObj(relay -> claimWhileHoldingLocks("relay-" + relay, claimsHeldOpen))
                        .toList();
        List<List<ClaimedOutboxEvent>> batches =
                Objects.requireNonNull(
                        Flux.merge(relays).collectList().block(Duration.ofSeconds(10)));

        assertThat(batches).hasSize(RELAY_COUNT);
        assertThat(batches).allSatisfy(batch -> assertThat(batch).hasSize(BATCH_SIZE));
        Set<String> actual = new HashSet<>();
        batches.forEach(
                batch ->
                        batch.forEach(
                                event ->
                                        assertThat(actual.add(event.eventId()))
                                                .as("event claimed by only one relay")
                                                .isTrue()));
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    private Mono<List<ClaimedOutboxEvent>> claimWhileHoldingLocks(
            String relayId, CyclicBarrier claimsHeldOpen) {
        Mono<List<ClaimedOutboxEvent>> claim =
                databaseClient
                        .sql("SET LOCAL ROLE app_outbox_relay")
                        .fetch()
                        .rowsUpdated()
                        .then(
                                databaseClient
                                        .sql(
                                                "SELECT set_config('app.platform_scope', "
                                                        + "'outbox_relay', true)")
                                        .fetch()
                                        .rowsUpdated())
                        .thenMany(
                                new OutboxClaimRepository(databaseClient)
                                        .claim(BATCH_SIZE, relayId, Instant.now().plusSeconds(30)))
                        .collectList()
                        .flatMap(
                                events ->
                                        Mono.fromCallable(
                                                        () -> {
                                                            awaitClaims(claimsHeldOpen);
                                                            return events;
                                                        })
                                                .subscribeOn(Schedulers.boundedElastic()));
        return transactions.transactional(claim);
    }

    private Set<String> seedPendingEvents(int count) throws SQLException {
        Set<String> ids = new HashSet<>();
        Instant createdAt = Instant.now().minusSeconds(60);
        try (Connection connection = clusterOwnerConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                INSERT INTO outbox.outbox_event (
                                    outbox_event_id, tenant_id, aggregate_type, aggregate_id,
                                    event_type, payload, correlation_id, occurred_at, created_at
                                ) VALUES (?, ?, 'Reference', ?,
                                    'platform.ReferenceEvent.v1', '{}'::jsonb,
                                    '01ARZ3NDEKTSV4RRFFQ69G5FAV', ?, ?)
                                """)) {
            for (int index = 0; index < count; index++) {
                UUID eventId =
                        UUID.fromString("01950f47-6000-7002-8000-%012d".formatted(index + 1));
                ids.add(eventId.toString());
                statement.setObject(1, eventId);
                statement.setObject(2, UUID.fromString("01950f47-6000-7000-8000-000000000001"));
                statement.setString(3, eventId.toString());
                statement.setTimestamp(4, Timestamp.from(createdAt.plusMillis(index)));
                statement.setTimestamp(5, Timestamp.from(createdAt.plusMillis(index)));
                statement.addBatch();
            }
            statement.executeBatch();
        }
        return ids;
    }

    private static void awaitClaims(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Claim barrier interrupted", exception);
        } catch (BrokenBarrierException | TimeoutException exception) {
            throw new IllegalStateException(
                    "A relay waited on another relay instead of skipping locked rows", exception);
        }
    }
}
