package org.meldtech.platform.platform.infra.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;

/**
 * Transaction-scoped relay singleton. FEAT-PLAT-006 adoption task P7.17 replaces only {@link
 * #lockKey(String)} with its central registry; this wrapper and its lock lifetime remain unchanged.
 */
final class RelaySingleton {

    static final String LOCK_SQL = "SELECT pg_try_advisory_xact_lock(:lockKey) AS acquired";
    private final DatabaseClient databaseClient;
    private final OutboxTelemetry telemetry;
    private final Clock clock;

    RelaySingleton(DatabaseClient databaseClient, OutboxTelemetry telemetry, Clock clock) {
        this.databaseClient = Objects.requireNonNull(databaseClient, "databaseClient");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Mono<Boolean> runIfLeader(String sweepName, Supplier<Mono<Void>> tick) {
        Objects.requireNonNull(tick, "tick");
        long key = lockKey(sweepName);
        Instant startedAt = Instant.now(clock);
        return TransactionSynchronizationManager.forCurrentTransaction()
                .then(
                        databaseClient
                                .sql(LOCK_SQL)
                                .bind("lockKey", key)
                                .map(
                                        (row, metadata) ->
                                                Boolean.TRUE.equals(
                                                        row.get("acquired", Boolean.class)))
                                .one())
                .flatMap(
                        acquired -> acquired ? Mono.defer(tick).thenReturn(true) : Mono.just(false))
                .doFinally(
                        ignored ->
                                telemetry.relayTick(
                                        Duration.between(startedAt, Instant.now(clock))));
    }

    static long lockKey(String sweepName) {
        byte[] bytes =
                Objects.requireNonNull(sweepName, "sweepName").getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            throw new IllegalArgumentException("Sweep name must be non-blank");
        }
        long hash = 0xcbf29ce484222325L;
        for (byte value : bytes) {
            hash ^= Byte.toUnsignedInt(value);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
