package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.SweepBatchResult;
import org.meldtech.platform.platform.api.SweepConnection;
import org.meldtech.platform.platform.api.SweepDataModule;
import org.meldtech.platform.platform.api.SweepMetadata;
import org.meldtech.platform.platform.api.SweepTransactions;
import org.meldtech.platform.platform.infra.persistence.ReferenceSweepPools;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OutboxBacklogIntegrationTest extends OutboxPostgreSqlIntegrationTestSupport {
    private ReferenceSweepPools pools;
    private PrometheusMeterRegistry meters;

    @BeforeEach
    void configureSecuredWorker() throws Exception {
        pools = new ReferenceSweepPools(postgres);
        meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    @AfterEach
    void closeWorker() {
        pools.close();
        meters.close();
    }

    @Test
    void productionWiringExportsCompleteMultiTenantSnapshotAndDrain() throws Exception {
        insertRows();
        runner().run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var sweep = context.getBean(OutboxBacklogSweep.class);
                            assertThat(meters.scrape()).contains("outbox_backlog_sample_fresh 0.0");
                            collect(sweep);
                            assertThat(meters.scrape())
                                    .contains(
                                            "outbox_backlog_depth{state=\"PENDING\"} 3.0",
                                            "outbox_backlog_depth{state=\"CLAIMED\"} 1.0",
                                            "outbox_backlog_depth{state=\"FAILED\"} 1.0",
                                            "outbox_backlog_depth{state=\"PUBLISHED\"} 1.0",
                                            "outbox_backlog_sample_fresh 1.0");
                            double firstAge = age();
                            assertThat(firstAge).isBetween(42.0, 60.0);
                            collect(sweep);
                            assertThat(age()).isGreaterThan(firstAge);
                            executeAsClusterOwner(
                                    """
                    UPDATE outbox.outbox_event SET state = 'PUBLISHED', published_at = now()
                    WHERE state = 'PENDING'
                    """);
                            collect(sweep);
                            assertThat(meters.scrape())
                                    .contains(
                                            "outbox_backlog_depth{state=\"PENDING\"} 0.0",
                                            "outbox_backlog_depth{state=\"PUBLISHED\"} 4.0",
                                            "outbox_oldest_pending_age_seconds 0.0");
                            assertThat(pools.acquiredConnections()).isZero();
                        });
    }

    @Test
    void emptySnapshotAndFutureCreatedAtHaveZeroAge() throws Exception {
        runner().run(
                        context -> {
                            var sweep = context.getBean(OutboxBacklogSweep.class);
                            collect(sweep);
                            assertThat(meters.get("outbox.backlog.depth").gauges())
                                    .allSatisfy(gauge -> assertThat(gauge.value()).isZero());
                            assertThat(age()).isZero();
                            insertRows();
                            executeAsClusterOwner(
                                    "DELETE FROM outbox.outbox_event WHERE created_at < now()");
                            collect(sweep);
                            assertThat(meters.scrape())
                                    .contains("outbox_backlog_depth{state=\"PENDING\"} 1.0");
                            assertThat(age()).isZero();
                        });
    }

    @Test
    void scopeIsRelayAcrossTenantsAndMissingOrMismatchedActorCannotPublish() throws Exception {
        insertRows();
        runner().run(
                        context -> {
                            var sweep = context.getBean(OutboxBacklogSweep.class);
                            StepVerifier.create(sweep.runBatch(() -> false))
                                    .expectError(SecurityException.class)
                                    .verify(Duration.ofSeconds(5));
                            StepVerifier.create(
                                            scoped(
                                                    sweep,
                                                    SystemActor.GRADING_WORKER,
                                                    sweep.runBatch(() -> false)))
                                    .expectError(SecurityException.class)
                                    .verify(Duration.ofSeconds(5));
                            assertThat(meters.scrape())
                                    .contains("outbox_backlog_depth{state=\"PENDING\"} NaN");
                            StepVerifier.create(
                                            queryText(
                                                    sweep,
                                                    """
                                                    SELECT current_user || ':'
                                                        || current_setting('app.platform_scope')
                                                    """))
                                    .assertNext(
                                            values ->
                                                    assertThat(values)
                                                            .containsExactly(
                                                                    "app_outbox_relay:outbox_relay"))
                                    .verifyComplete();
                            collect(sweep);
                            assertThat(meters.scrape())
                                    .contains("outbox_backlog_depth{state=\"PENDING\"} 3.0");
                        });
    }

    @Test
    void databaseFailureTimeoutAndCancellationRetainLastGoodSampleAndReleaseConnection()
            throws Exception {
        insertRows();
        runner().withPropertyValues("cbt.outbox.backlog.query-timeout=100ms")
                .run(
                        context -> {
                            var sweep = context.getBean(OutboxBacklogSweep.class);
                            collect(sweep);
                            try (var owner = clusterOwnerConnection();
                                    var statement = owner.createStatement()) {
                                owner.setAutoCommit(false);
                                statement.execute(
                                        "LOCK TABLE outbox.outbox_event IN ACCESS EXCLUSIVE MODE");
                                StepVerifier.create(
                                                scoped(
                                                        sweep,
                                                        SystemActor.OUTBOX_RELAY,
                                                        sweep.runBatch(() -> false)))
                                        .expectError(TimeoutException.class)
                                        .verify(Duration.ofSeconds(5));
                                awaitReleased();
                                StepVerifier.create(
                                                scoped(
                                                        sweep,
                                                        SystemActor.OUTBOX_RELAY,
                                                        sweep.runBatch(() -> false)))
                                        .thenAwait(Duration.ofMillis(25))
                                        .thenCancel()
                                        .verify(Duration.ofSeconds(5));
                                awaitReleased();
                                owner.rollback();
                            }
                            executeAsClusterOwner(
                                    "REVOKE SELECT ON outbox.outbox_event FROM app_outbox_relay");
                            try {
                                StepVerifier.create(
                                                scoped(
                                                        sweep,
                                                        SystemActor.OUTBOX_RELAY,
                                                        sweep.runBatch(() -> false)))
                                        .expectError()
                                        .verify(Duration.ofSeconds(5));
                                assertThat(meters.scrape())
                                        .contains("outbox_backlog_depth{state=\"PENDING\"} 3.0");
                                assertThat(
                                                meters.get("outbox.backlog.collection.failure")
                                                        .counter()
                                                        .count())
                                        .isEqualTo(3);
                            } finally {
                                executeAsClusterOwner(
                                        "GRANT SELECT ON outbox.outbox_event TO app_outbox_relay");
                            }
                            collect(sweep);
                            awaitReleased();
                        });
    }

    @Test
    void disabledCollectorCannotSatisfyCollectionAcceptance() {
        runner().run(
                        context -> {
                            var sweep = context.getBean(OutboxBacklogSweep.class);
                            StepVerifier.create(sweep.runBatch(() -> true))
                                    .expectNext(new SweepBatchResult(0, false))
                                    .verifyComplete();
                            assertThat(
                                            queryInt(
                                                    """
                                                    SELECT count(*) FROM platform.sweep_registry
                                                    WHERE sweep_name = 'outbox-backlog' AND enabled
                                                    """))
                                    .isZero();
                            assertThat(meters.scrape())
                                    .contains(
                                            "outbox_backlog_sample_fresh 0.0",
                                            "outbox_backlog_depth{state=\"PENDING\"} NaN");
                        });
    }

    @Test
    void representativePlanRecordsCostWithoutAddingAnUnjustifiedIndex() throws Exception {
        insertRows();
        executeAsClusterOwner(
                """
                INSERT INTO outbox.outbox_event
                    (outbox_event_id, tenant_id, aggregate_type, aggregate_id, event_type,
                     payload, correlation_id, occurred_at, created_at)
                SELECT gen_random_uuid(), gen_random_uuid(), 'Probe', 'plan', 'delivery.Probe.v1',
                       '{}', '01ARZ3NDEKTSV4RRFFQ69G5FAV', now(), now()
                FROM generate_series(1, 10000)
                """);
        runner().run(
                        context -> {
                            var sweep = context.getBean(OutboxBacklogSweep.class);
                            long started = System.nanoTime();
                            collect(sweep);
                            System.out.printf(
                                    "OUTBOX_BACKLOG_SAMPLE rows=10006 elapsed_ms=%d "
                                            + "budget_status=OWNER_REVIEW_PENDING%n",
                                    Duration.ofNanos(System.nanoTime() - started).toMillis());
                            StepVerifier.create(
                                            queryText(
                                                    sweep,
                                                    "EXPLAIN (ANALYZE, BUFFERS) "
                                                            + DatabaseOutboxBacklogReader
                                                                    .SNAPSHOT_SQL))
                                    .assertNext(
                                            plan -> {
                                                assertThat(plan)
                                                        .anyMatch(
                                                                line ->
                                                                        line.contains(
                                                                                "Execution Time"));
                                                plan.forEach(System.out::println);
                                            })
                                    .verifyComplete();
                        });
    }

    private Mono<List<String>> queryText(OutboxBacklogSweep sweep, String sql) {
        return scoped(
                sweep,
                SystemActor.OUTBOX_RELAY,
                pools.transactions()
                        .inPlatformTransaction(
                                SweepDataModule.OUTBOX,
                                connection -> readTextRows(connection, sql).collectList()));
    }

    private static Flux<String> readTextRows(SweepConnection connection, String sql) {
        return Flux.from(connection.createStatement(sql).execute())
                .concatMap(result -> result.map((row, metadata) -> row.get(0, String.class)));
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(OutboxTelemetryConfiguration.class)
                .withBean(MeterRegistry.class, () -> meters)
                .withBean(SweepTransactions.class, pools::transactions)
                .withPropertyValues("spring.profiles.active=worker");
    }

    private void collect(OutboxBacklogSweep sweep) {
        StepVerifier.create(scoped(sweep, SystemActor.OUTBOX_RELAY, sweep.runBatch(() -> false)))
                .expectNext(new SweepBatchResult(0, false))
                .verifyComplete();
    }

    private static <T> Mono<T> scoped(OutboxBacklogSweep sweep, SystemActor actor, Mono<T> work) {
        return work.contextWrite(
                context ->
                        context.put(SweepMetadata.class, sweep.metadata())
                                .put(
                                        ActorContext.class,
                                        ActorContext.platformSystem(
                                                actor,
                                                new CorrelationId("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                                                new SourceIp("::1"))));
    }

    private double age() {
        return meters.get("outbox.oldest.pending.age.seconds").gauge().value();
    }

    private void awaitReleased() {
        StepVerifier.create(
                        Flux.interval(Duration.ofMillis(10))
                                .filter(ignored -> pools.acquiredConnections() == 0)
                                .next())
                .expectNextCount(1)
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    private void insertRows() throws Exception {
        executeAsClusterOwner(
                """
                INSERT INTO outbox.outbox_event
                    (outbox_event_id, tenant_id, aggregate_type, aggregate_id, event_type, payload,
                     correlation_id, occurred_at, created_at, next_attempt_at, state,
                     claim_expires_at, claimed_by, published_at, attempt_count, last_error)
                SELECT gen_random_uuid(),
                       CASE WHEN ordinal % 2 = 0 THEN '00000000-0000-0000-0000-000000000001'::uuid
                            ELSE '00000000-0000-0000-0000-000000000002'::uuid END,
                       'Probe', ordinal::text, 'delivery.Probe.v1', '{}',
                       '01ARZ3NDEKTSV4RRFFQ69G5FAV', now() - interval '5 days',
                       CASE WHEN ordinal = 3 THEN now() + interval '1 minute'
                            ELSE now() - interval '42 seconds' END,
                       now() + interval '1 day', state,
                       CASE WHEN state = 'CLAIMED' THEN now() + interval '1 minute' END,
                       CASE WHEN state = 'CLAIMED' THEN 'test-worker' END,
                       CASE WHEN state = 'PUBLISHED' THEN now() END,
                       CASE WHEN state = 'FAILED' THEN 8 ELSE 0 END,
                       CASE WHEN state = 'FAILED' THEN 'BROKER_NACK' END
                FROM (VALUES (1, 'PENDING'), (2, 'PENDING'), (3, 'PENDING'),
                             (4, 'CLAIMED'), (5, 'PUBLISHED'), (6, 'FAILED')) AS fixture(ordinal, state)
                """);
    }
}
