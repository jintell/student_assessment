package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class RelaySingletonIntegrationTest extends OutboxPostgreSqlIntegrationTestSupport {

    @Test
    void onlyOneReplicaRunsTheTickWhileTheAdvisoryLockIsHeld() throws Exception {
        RelaySingleton singleton =
                new RelaySingleton(databaseClient, mock(OutboxTelemetry.class), Clock.systemUTC());
        CountDownLatch leaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLeader = new CountDownLatch(1);
        AtomicInteger activeTicks = new AtomicInteger();

        var leader =
                transactions
                        .transactional(
                                singleton.runIfLeader(
                                        "outbox-relay-contention",
                                        () -> heldTick(activeTicks, leaderStarted, releaseLeader)))
                        .subscribeOn(Schedulers.boundedElastic())
                        .toFuture();
        assertThat(leaderStarted.await(5, TimeUnit.SECONDS)).isTrue();

        List<Boolean> competitors =
                Objects.requireNonNull(
                        Flux.range(0, 5)
                                .flatMap(
                                        replica ->
                                                transactions
                                                        .transactional(
                                                                singleton.runIfLeader(
                                                                        "outbox-relay-contention",
                                                                        () ->
                                                                                Mono.error(
                                                                                        new AssertionError(
                                                                                                "competing tick ran"))))
                                                        .subscribeOn(Schedulers.boundedElastic()))
                                .collectList()
                                .block(Duration.ofSeconds(5)));

        assertThat(competitors).containsOnly(false).hasSize(5);
        assertThat(activeTicks).hasValue(1);
        releaseLeader.countDown();
        assertThat(leader.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(activeTicks).hasValue(0);
    }

    @Test
    void losingTheDatabaseLockStopsTheTickBeforeAReplacementPublishes() throws Exception {
        String sweepName = "outbox-relay-lock-loss";
        RelaySingleton singleton =
                new RelaySingleton(databaseClient, mock(OutboxTelemetry.class), Clock.systemUTC());
        CountDownLatch firstTickStarted = new CountDownLatch(1);
        AtomicInteger activeTicks = new AtomicInteger();
        AtomicInteger replacementPublications = new AtomicInteger();

        var interruptedLeader =
                transactions
                        .transactional(
                                singleton.runIfLeader(
                                        sweepName,
                                        () -> databaseSleepTick(activeTicks, firstTickStarted)))
                        .subscribeOn(Schedulers.boundedElastic())
                        .toFuture();
        assertThat(firstTickStarted.await(5, TimeUnit.SECONDS)).isTrue();
        int lockHolder = findAdvisoryLockHolder();
        terminateBackend(lockHolder);

        assertThatThrownBy(() -> interruptedLeader.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
        awaitNoActiveTick(activeTicks);

        Boolean replacementLed =
                transactions
                        .transactional(
                                singleton.runIfLeader(
                                        sweepName,
                                        () ->
                                                Mono.fromRunnable(
                                                        replacementPublications::incrementAndGet)))
                        .block(Duration.ofSeconds(5));
        assertThat(replacementLed).isTrue();
        assertThat(replacementPublications).hasValue(1);
        assertThat(activeTicks).hasValue(0);
    }

    private Mono<Void> heldTick(
            AtomicInteger activeTicks, CountDownLatch started, CountDownLatch release) {
        return Mono.fromCallable(
                        () -> {
                            activeTicks.incrementAndGet();
                            started.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Leader tick was not released");
                            }
                            return true;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(ignored -> activeTicks.decrementAndGet())
                .then();
    }

    private Mono<Void> databaseSleepTick(AtomicInteger activeTicks, CountDownLatch started) {
        return Mono.defer(
                        () -> {
                            activeTicks.incrementAndGet();
                            started.countDown();
                            return databaseClient
                                    .sql("SELECT pg_sleep(30)")
                                    .fetch()
                                    .rowsUpdated()
                                    .then();
                        })
                .doFinally(ignored -> activeTicks.decrementAndGet());
    }

    private int findAdvisoryLockHolder() throws SQLException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection connection = clusterOwnerConnection();
                    var statement = connection.createStatement();
                    var result =
                            statement.executeQuery(
                                    """
                                    SELECT pid
                                    FROM pg_catalog.pg_locks
                                    WHERE locktype = 'advisory' AND granted
                                    ORDER BY pid
                                    LIMIT 1
                                    """)) {
                if (result.next()) {
                    return result.getInt(1);
                }
            }
            Thread.onSpinWait();
        }
        throw new IllegalStateException("Advisory lock holder did not appear");
    }

    private void terminateBackend(int backendPid) throws SQLException {
        try (Connection connection = clusterOwnerConnection();
                var statement = connection.prepareStatement("SELECT pg_terminate_backend(?)")) {
            statement.setInt(1, backendPid);
            try (var result = statement.executeQuery()) {
                result.next();
                assertThat(result.getBoolean(1)).isTrue();
            }
        }
    }

    private static void awaitNoActiveTick(AtomicInteger activeTicks) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (activeTicks.get() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(activeTicks).hasValue(0);
    }
}
