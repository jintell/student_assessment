package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.RuntimeRole;
import org.meldtech.platform.platform.api.SweepBatchResult;
import org.meldtech.platform.platform.api.SweepLock;
import org.meldtech.platform.platform.infra.outbox.OutboxPostgreSqlIntegrationTestSupport;
import org.meldtech.platform.platform.infra.persistence.ReferenceSweepPools;
import org.meldtech.platform.runtimefixture.ReferenceSweep;
import org.meldtech.platform.shared.infra.web.RequestContextPropagation;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;
import tools.jackson.databind.json.JsonMapper;

class ReferenceSweepIntegrationTest extends OutboxPostgreSqlIntegrationTestSupport {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private ReferenceSweepPools pools;

    @BeforeEach
    void installTestOnlyRegistryAndWork() throws Exception {
        pools = new ReferenceSweepPools(postgres);
        executeAsClusterOwner(
                """
                INSERT INTO platform.sweep_registry
                    (sweep_name, lock_key, owning_module, owning_feature, default_interval_ms, enabled, system_actor)
                VALUES ('reference-sweep', 94626001, 'delivery', 'FEAT-PLAT-006', 100, true, 'AUTO_SUBMIT_SWEEPER');
                CREATE TABLE delivery.reference_sweep_work
                    (id integer PRIMARY KEY, completed boolean NOT NULL DEFAULT false);
                ALTER TABLE delivery.reference_sweep_work ENABLE ROW LEVEL SECURITY;
                ALTER TABLE delivery.reference_sweep_work FORCE ROW LEVEL SECURITY;
                CREATE POLICY reference_scope ON delivery.reference_sweep_work FOR ALL TO app_delivery
                    USING (current_setting('app.platform_scope', true) = 'auto_submit_sweep')
                    WITH CHECK (current_setting('app.platform_scope', true) = 'auto_submit_sweep');
                GRANT SELECT, UPDATE ON delivery.reference_sweep_work TO app_delivery;
                INSERT INTO delivery.reference_sweep_work (id) VALUES (1), (2);
                """);
    }

    @AfterEach
    void removeTestOnlyRegistryAndWork() throws Exception {
        pools.close();
        executeAsClusterOwner(
                """
                DELETE FROM platform.sweep_registry WHERE sweep_name = 'reference-sweep';
                DROP TABLE IF EXISTS delivery.reference_sweep_work;
                """);
    }

    @Test
    void gracefulInterruptionCommitsOneBatchAndReleasesTheLockLast() throws Exception {
        var entered = new CountDownLatch(1);
        var observed = new AtomicReference<ReferenceSweep.Observation>();
        Sinks.Empty<Void> commit = Sinks.empty();
        var sweep =
                new ReferenceSweep(
                        pools.transactions(),
                        observation -> {
                            observed.set(observation);
                            entered.countDown();
                            return commit.asMono();
                        });
        try (var runtime = new SweepRuntime(sweep)) {
            runtime.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var observation = Objects.requireNonNull(observed.get());
            assertThat(observation.databaseRole()).isEqualTo("app_delivery");
            assertThat(observation.platformScope()).isEqualTo("auto_submit_sweep");
            assertThat(observation.actor().systemActorName())
                    .contains(SystemActor.AUTO_SUBMIT_SWEEPER);
            assertThat(observation.actor().tenantId()).isEmpty();
            assertThat(completed()).isZero();
            assertContended();
            var drained = runtime.beginDrain();
            assertThat(drained).isNotDone();
            commit.tryEmitEmpty();
            drained.get(5, TimeUnit.SECONDS);
            assertThat(completed()).isEqualTo(1);
            assertContended();
            runtime.releaseLocks();
            assertThat(pools.acquiredConnections()).isZero();
            StepVerifier.create(
                            Mono.usingWhen(
                                    new SessionSweepLock(pools.locks())
                                            .tryAcquire(ReferenceSweep.NAME),
                                    handle -> handle.assertHeld().thenReturn(true),
                                    SweepLock.Handle::close))
                    .expectNext(true)
                    .verifyComplete();
        } finally {
            commit.tryEmitEmpty();
        }
    }

    @Test
    void timedOutBatchRollsBackAndNextRunnerResumesFromDurableWork() throws Exception {
        var entered = new CountDownLatch(1);
        var sweep =
                new ReferenceSweep(
                        pools.transactions(),
                        observation -> {
                            entered.countDown();
                            return Mono.never();
                        });
        try (var runtime = new SweepRuntime(sweep)) {
            runtime.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var drained = runtime.beginDrain();
            runtime.scheduler.advanceTimeBy(Duration.ofSeconds(4));
            drained.get(5, TimeUnit.SECONDS);
            runtime.releaseLocks();
            assertThat(completed()).isZero();
            assertThat(pools.acquiredConnections()).isZero();
            assertThat(
                            runtime.meters
                                    .get("sweep.tick.outcome")
                                    .tag("outcome", "TIMEOUT")
                                    .counter()
                                    .count())
                    .isEqualTo(1);
        }
        var resumed = new ReferenceSweep(pools.transactions(), observation -> Mono.empty());
        var registry = new SweepRegistryLoader(pools.worker(), List.of(resumed));
        registry.afterSingletonsInstantiated();
        // Use the next replica's real handle and context-installed batch loop, with no retained
        // cursor.
        try (var runtime = new SweepRuntime(resumed)) {
            runtime.runner.start();
            StepVerifier.create(runtime.runner.runTick(registry.enabledSweeps().getFirst()))
                    .expectComplete()
                    .verify(WAIT);
            assertThat(completed()).isEqualTo(2);
        }
    }

    @Test
    void shutdownReleasesTheLockForAnotherReplicasNextTick() throws Exception {
        var firstEntered = new CountDownLatch(1);
        Sinks.Empty<Void> commit = Sinks.empty();
        var firstSweep =
                new ReferenceSweep(
                        pools.transactions(),
                        observation -> {
                            firstEntered.countDown();
                            return commit.asMono();
                        });
        var secondEntered = new CountDownLatch(1);
        var secondSweep =
                new ReferenceSweep(
                        pools.transactions(),
                        observation -> {
                            secondEntered.countDown();
                            return Mono.empty();
                        });
        try (var first = new SweepRuntime(firstSweep);
                var second = new SweepRuntime(secondSweep)) {
            first.start();
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();

            var drained = first.beginDrain();
            commit.tryEmitEmpty();
            drained.get(5, TimeUnit.SECONDS);
            first.releaseLocks();

            second.runner.start();
            second.scheduler.advanceTimeBy(Duration.ofMillis(99));
            assertThat(secondEntered.getCount()).isEqualTo(1);
            second.scheduler.advanceTimeBy(Duration.ofMillis(1));
            assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            commit.tryEmitEmpty();
        }
    }

    @Test
    void shutdownSequenceCompletesWithinTheGracePeriodWithMargin() throws Exception {
        var entered = new CountDownLatch(1);
        Sinks.Empty<Void> commit = Sinks.empty();
        var sweep =
                new ReferenceSweep(
                        pools.transactions(),
                        observation -> {
                            entered.countDown();
                            return commit.asMono();
                        });
        try (var runtime = new SweepRuntime(sweep)) {
            runtime.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            var drained = runtime.beginDrain();
            commit.tryEmitEmpty();
            drained.get(5, TimeUnit.SECONDS);
            runtime.releaseLocks();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            long graceMarginMillis = Duration.ofSeconds(30).minus(elapsed).toMillis();

            assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
            assertThat(graceMarginMillis).isGreaterThanOrEqualTo(25_000);
            System.out.printf(
                    "P7.16 shutdown_sequence_ms=%d termination_grace_ms=60000 margin_after_prestop_ms=%d%n",
                    elapsed.toMillis(), graceMarginMillis);
        } finally {
            commit.tryEmitEmpty();
        }
    }

    @Test
    void registryAndSecurityContextCannotBeBypassed() throws Exception {
        var sweep = new ReferenceSweep(pools.transactions(), observation -> Mono.empty());
        StepVerifier.create(sweep.runBatch(() -> false))
                .expectError(SecurityException.class)
                .verify(WAIT);
        StepVerifier.create(sweep.runBatch(() -> true))
                .expectNext(new SweepBatchResult(0, false))
                .verifyComplete();
        assertThat(completed()).isZero();
        executeAsClusterOwner(
                "DELETE FROM platform.sweep_registry WHERE sweep_name = 'reference-sweep'");
        var registry = new SweepRegistryLoader(pools.worker(), List.of(sweep));
        assertThatThrownBy(registry::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no registry row");
    }

    @Test
    void referenceExistsOnlyInTestArtifactAndExplicitManifestOverlay() throws Exception {
        Path production =
                Path.of(
                        SweepRunner.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());
        assertThat(production.resolve(ReferenceSweep.class.getName().replace('.', '/') + ".class"))
                .doesNotExist();
        for (RuntimeRole role : RuntimeRole.values()) {
            assertThat(RuntimeRoleManifest.load(role).entryPoints())
                    .noneMatch(entry -> ReferenceSweep.NAME.equals(entry.sweepName()));
        }
        var mapper = new JsonMapper();
        var overlay = mapper.valueToTree(RuntimeRoleManifest.load(RuntimeRole.WORKER));
        ((tools.jackson.databind.node.ObjectNode) overlay)
                .putArray("entryPoints")
                .add(
                        mapper.valueToTree(
                                new RuntimeRoleManifest.EntryPoint(
                                        ReferenceSweep.class.getName(),
                                        "sweep",
                                        null,
                                        null,
                                        null,
                                        ReferenceSweep.NAME,
                                        null,
                                        null,
                                        null)));
        var manifest = mapper.treeToValue(overlay, RuntimeRoleManifest.class);
        var verifier = new RuntimeManifestVerifier(RuntimeRole.WORKER, manifest);
        new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=worker")
                .withBean(
                        ReferenceSweep.class,
                        () -> new ReferenceSweep(pools.transactions(), ignored -> Mono.empty()))
                .withBean(RuntimeManifestVerifier.class, () -> verifier)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(verifier.verifiedEntries()).hasSize(1);
                        });
    }

    private int completed() throws Exception {
        return queryInt("SELECT count(*) FROM delivery.reference_sweep_work WHERE completed");
    }

    private void assertContended() {
        StepVerifier.create(new SessionSweepLock(pools.locks()).tryAcquire(ReferenceSweep.NAME))
                .expectComplete()
                .verify(WAIT);
    }

    private final class SweepRuntime implements AutoCloseable {
        private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
        private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        private final SweepLockShutdown shutdown = new SweepLockShutdown(Mono.empty());
        private final SweepRunner runner;

        private SweepRuntime(ReferenceSweep sweep) {
            var registry = new SweepRegistryLoader(pools.worker(), List.of(sweep));
            registry.afterSingletonsInstantiated();
            assertThat(registry.enabledSweeps()).hasSize(1);
            runner =
                    new SweepRunner(
                            registry::enabledSweeps,
                            new SessionSweepLock(pools.locks()),
                            () -> new CorrelationId("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                            new RequestContextPropagation(),
                            scheduler,
                            shutdown,
                            new SweepTelemetry(meters, () -> Instant.EPOCH));
        }

        private void start() {
            runner.start();
            scheduler.advanceTimeBy(Duration.ofMillis(100));
        }

        private CompletableFuture<Void> beginDrain() {
            var drained = new CompletableFuture<Void>();
            runner.stop(() -> drained.complete(null));
            return drained;
        }

        private void releaseLocks() throws Exception {
            var released = new CompletableFuture<Void>();
            shutdown.stop(() -> released.complete(null));
            released.get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            try {
                var drained = beginDrain();
                scheduler.advanceTimeBy(Duration.ofSeconds(5));
                drained.get(5, TimeUnit.SECONDS);
                releaseLocks();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while draining reference sweep", interrupted);
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot drain reference sweep", failure);
            } finally {
                scheduler.dispose();
                meters.close();
            }
        }
    }
}
