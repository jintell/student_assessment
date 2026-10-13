package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import io.r2dbc.spi.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.SweepBatchResult;
import org.meldtech.platform.platform.api.SweepDefinition;
import org.meldtech.platform.platform.api.SweepMetadata;
import org.meldtech.platform.platform.api.SweepStopSignal;
import org.meldtech.platform.platform.infra.runtime.SweepRegistryLoader.RegistryRow;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import org.mockito.ArgumentMatchers;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class SweepRegistryLoaderTest {

    @Test
    void rejectsCollidingKeysIncludingDisabledRowsBeforeLifecycleActivation() {
        for (boolean enabled : List.of(false, true)) {
            for (long key : new long[] {Long.MIN_VALUE, 0, Long.MAX_VALUE}) {
                var rows =
                        List.of(
                                rowWithKey("first", key, enabled),
                                rowWithKey("second", key, false));
                AtomicBoolean started = new AtomicBoolean();
                new ApplicationContextRunner()
                        .withUserConfiguration(SweepRegistryLoader.class)
                        .withPropertyValues("spring.profiles.active=worker")
                        .withBean(
                                "workerConnectionFactory",
                                ConnectionFactory.class,
                                () -> registryFactory(rows))
                        .withBean(
                                "listener",
                                SmartLifecycle.class,
                                () -> new RecordingLifecycle(started))
                        .run(
                                context -> {
                                    assertThat(context).hasFailed();
                                    assertThat(context.getStartupFailure())
                                            .hasMessageContaining(
                                                    "lock-key collision between first and second");
                                    assertThat(started).isFalse();
                                });
            }
        }
    }

    @Test
    void acceptsDistinctSignedKeysWithoutDerivingOrRewritingThem() {
        assertThat(
                        SweepRegistryLoader.validate(
                                List.of(
                                        rowWithKey("first", Long.MIN_VALUE, false),
                                        rowWithKey("second", Long.MAX_VALUE, false)),
                                List.of()))
                .isEmpty();
    }

    private static RegistryRow rowWithKey(String name, long key, boolean enabled) {
        return new RegistryRow(
                name,
                key,
                "platform",
                "FEAT-PLAT-006",
                100,
                enabled,
                SystemActor.RETENTION_ENGINE.name());
    }

    @Test
    void rejectsDeclaredIntervalAndBatchBoundsEvenWhenTheStoredIntervalIsValid() {
        for (SweepMetadata metadata :
                List.of(
                        metadata(
                                Duration.ofMillis(99),
                                Duration.ofMillis(200),
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(5)),
                        metadata(
                                Duration.ofMillis(100),
                                Duration.ofDays(2),
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(5)),
                        metadata(
                                Duration.ofMillis(100),
                                Duration.ofMillis(200),
                                Duration.ofSeconds(5),
                                Duration.ofSeconds(5)),
                        metadata(
                                Duration.ofMillis(100),
                                Duration.ofMillis(200),
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(301)))) {
            SweepDefinition definition = mock(SweepDefinition.class);
            when(definition.metadata()).thenReturn(metadata);
            assertThatIllegalStateException()
                    .isThrownBy(
                            () ->
                                    SweepRegistryLoader.validate(
                                            List.of(row("reference", 100, true)),
                                            List.of(definition)))
                    .withMessageContaining("runtime bounds");
        }
    }

    @Test
    void missingRowAbortsContextBeforeLifecycleStartsAndUsesTheWorkerQualifier() {
        for (boolean missingDefinitionRow : List.of(false, true)) {
            AtomicBoolean started = new AtomicBoolean();
            ConnectionFactory decoy = mock(ConnectionFactory.class);
            ApplicationContextRunner runner =
                    new ApplicationContextRunner()
                            .withUserConfiguration(SweepRegistryLoader.class)
                            .withPropertyValues("spring.profiles.active=worker")
                            .withBean(
                                    "workerConnectionFactory",
                                    ConnectionFactory.class,
                                    SweepRegistryLoaderTest::emptyRegistryFactory)
                            .withBean(
                                    "sweepLockConnectionFactory",
                                    ConnectionFactory.class,
                                    () -> decoy)
                            .withBean(
                                    "listener",
                                    SmartLifecycle.class,
                                    () -> new RecordingLifecycle(started));
            if (missingDefinitionRow) {
                runner = runner.withBean(SweepDefinition.class, Definition::new);
            }
            runner.run(
                    context -> {
                        if (missingDefinitionRow) {
                            assertThat(context).hasFailed();
                            assertThat(started).isFalse();
                        } else {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(SweepRegistryLoader.class).enabledSweeps())
                                    .isEmpty();
                            assertThat(started).isTrue();
                        }
                        verifyNoInteractions(decoy);
                    });
        }
    }

    @Test
    void failedReloadRevokesThePreviouslyValidatedSnapshot() {
        ConnectionFactory factory = emptyRegistryFactory();
        var loader = new SweepRegistryLoader(factory, List.of());
        loader.afterSingletonsInstantiated();
        assertThat(loader.enabledSweeps()).isEmpty();
        doReturn(Mono.error(new IllegalStateException("unavailable"))).when(factory).create();
        assertThatIllegalStateException().isThrownBy(loader::afterSingletonsInstantiated);
        assertThatIllegalStateException().isThrownBy(loader::enabledSweeps);
    }

    private static SweepMetadata metadata(
            Duration minimum, Duration maximum, Duration batch, Duration tick) {
        return new SweepMetadata(
                "reference",
                "platform",
                "FEAT-PLAT-006",
                SystemActor.RETENTION_ENGINE,
                Duration.ofMillis(100),
                minimum,
                maximum,
                batch,
                tick);
    }

    private static ConnectionFactory emptyRegistryFactory() {
        return registryFactory(List.of());
    }

    private static ConnectionFactory registryFactory(List<RegistryRow> rows) {
        Connection connection = mock(Connection.class);
        doReturn(Mono.empty()).when(connection).beginTransaction();
        doReturn(Mono.empty()).when(connection).commitTransaction();
        doReturn(Mono.empty()).when(connection).rollbackTransaction();
        doReturn(Mono.empty()).when(connection).close();
        Statement statement = mock(Statement.class);
        Result result = mock(Result.class);
        doReturn(Flux.fromIterable(rows))
                .when(result)
                .map(ArgumentMatchers.<BiFunction<Row, RowMetadata, RegistryRow>>any());
        doReturn(Mono.just(result)).when(statement).execute();
        when(connection.createStatement(anyString())).thenReturn(statement);
        ConnectionFactory factory = mock(ConnectionFactory.class);
        doReturn(Mono.just(connection)).when(factory).create();
        return factory;
    }

    private record RecordingLifecycle(AtomicBoolean started) implements SmartLifecycle {
        @Override
        public void start() {
            started.set(true);
        }

        @Override
        public void stop() {
            started.set(false);
        }

        @Override
        public boolean isRunning() {
            return started.get();
        }
    }

    @Test
    void schedulesOnlyEnabledDeclaredRowsAndAllowsDisabledFutureRows() {
        var definition = new Definition();
        var enabled =
                SweepRegistryLoader.validate(
                        List.of(row("reference", 100, true), rowWithKey("future", 42L, false)),
                        List.of(definition));
        assertThat(enabled)
                .containsExactly(
                        new SweepRegistryLoader.RegisteredSweep(
                                definition, Duration.ofMillis(100)));
        assertThat(
                        SweepRegistryLoader.validate(
                                List.of(row("reference", 100, false)), List.of(definition)))
                .isEmpty();
    }

    @Test
    void rejectsMissingRowsEvenForDisabledDefinitions() {
        assertThatIllegalStateException()
                .isThrownBy(
                        () -> SweepRegistryLoader.validate(List.of(), List.of(new Definition())))
                .withMessageContaining("no registry row");
        assertThatIllegalStateException()
                .isThrownBy(
                        () ->
                                SweepRegistryLoader.validate(
                                        List.of(row("reference", 100, true)), List.of()))
                .withMessageContaining("no definition");
    }

    @Test
    void validatesEveryRowIncludingDisabledRows() {
        for (long interval : new long[] {0, 99, 86_400_001}) {
            assertThatIllegalStateException()
                    .isThrownBy(
                            () ->
                                    SweepRegistryLoader.validate(
                                            List.of(row("future", interval, false)), List.of()))
                    .withMessageContaining("interval");
        }
        assertThatIllegalStateException()
                .isThrownBy(
                        () ->
                                SweepRegistryLoader.validate(
                                        List.of(row("auto-submit", 200, false)), List.of()))
                .withMessageContaining("cadence");
    }

    @Test
    void rejectsDuplicatesAndMismatchedMetadata() {
        var row = row("reference", 100, false);
        assertThatIllegalStateException()
                .isThrownBy(() -> SweepRegistryLoader.validate(List.of(row, row), List.of()))
                .withMessageContaining("duplicate");
        assertThatIllegalStateException()
                .isThrownBy(
                        () ->
                                SweepRegistryLoader.validate(
                                        List.of(row), List.of(new Definition(), new Definition())))
                .withMessageContaining("duplicate");
        assertThatIllegalStateException()
                .isThrownBy(
                        () ->
                                SweepRegistryLoader.validate(
                                        List.of(row("reference", 200, true)),
                                        List.of(new Definition())))
                .withMessageContaining("disagree");
        var wrongActor =
                new SweepRegistryLoader.RegistryRow(
                        "reference",
                        41L,
                        "platform",
                        "FEAT-PLAT-006",
                        100,
                        false,
                        SystemActor.GRADING_WORKER.name());
        assertThatIllegalStateException()
                .isThrownBy(
                        () ->
                                SweepRegistryLoader.validate(
                                        List.of(wrongActor), List.of(new Definition())))
                .withMessageContaining("disagree");
    }

    @Test
    void acceptsEveryClosedActorNameForDisabledFutureRows() {
        for (SystemActor actor : SystemActor.values()) {
            var row = rowWithActor("future", false, actor.name());
            assertThat(SweepRegistryLoader.validate(List.of(row), List.of())).isEmpty();
        }
    }

    @Test
    void rejectsUnknownOrNonCanonicalActorNamesEvenOnDisabledRows() {
        for (String actorName :
                List.of("UNKNOWN", "retention_engine", " RETENTION_ENGINE", "RETENTION_ENGINE ")) {
            for (boolean enabled : new boolean[] {false, true}) {
                var row = rowWithActor("reference", enabled, actorName);
                assertThatIllegalStateException()
                        .isThrownBy(
                                () ->
                                        SweepRegistryLoader.validate(
                                                List.of(row), List.of(new Definition())))
                        .withMessage("SWEEP_REGISTRY_INVALID: unknown system actor");
            }
        }
    }

    @Test
    void missingDatabaseFailsBootstrapWithoutExposingDriverDetails() {
        ConnectionFactory connections = mock(ConnectionFactory.class);
        doReturn(Mono.error(new IllegalStateException("sensitive driver detail")))
                .when(connections)
                .create();
        var loader = new SweepRegistryLoader(connections, List.of());
        assertThatIllegalStateException().isThrownBy(loader::enabledSweeps);
        assertThatIllegalStateException()
                .isThrownBy(loader::afterSingletonsInstantiated)
                .withMessage("SWEEP_REGISTRY_INVALID: registry load or validation failed")
                .withNoCause();
        assertThatIllegalStateException().isThrownBy(loader::enabledSweeps);
    }

    private static SweepRegistryLoader.RegistryRow row(
            String name, long interval, boolean enabled) {
        return new SweepRegistryLoader.RegistryRow(
                name,
                41L,
                "platform",
                "FEAT-PLAT-006",
                interval,
                enabled,
                SystemActor.RETENTION_ENGINE.name());
    }

    private static SweepRegistryLoader.RegistryRow rowWithActor(
            String name, boolean enabled, String actorName) {
        return new SweepRegistryLoader.RegistryRow(
                name, 41L, "platform", "FEAT-PLAT-006", 100, enabled, actorName);
    }

    private static final class Definition implements SweepDefinition {
        @Override
        public SweepMetadata metadata() {
            return new SweepMetadata(
                    "reference",
                    "platform",
                    "FEAT-PLAT-006",
                    SystemActor.RETENTION_ENGINE,
                    Duration.ofMillis(100),
                    Duration.ofMillis(100),
                    Duration.ofMillis(200),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(5));
        }

        @Override
        public Mono<SweepBatchResult> runBatch(SweepStopSignal stopSignal) {
            return Mono.error(new AssertionError("Registry validation must not execute work"));
        }
    }
}
