package org.meldtech.platform.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class SweepDefinitionTest {

    @Test
    void validatesIdentityAndIntervalBounds() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> metadata("Not canonical", 100, 100, 100));
        assertThatIllegalArgumentException().isThrownBy(() -> metadata("reference", 99, 100, 200));
        assertThatIllegalArgumentException().isThrownBy(() -> metadata("reference", 201, 100, 200));
        assertThatIllegalArgumentException().isThrownBy(() -> metadata("reference", 100, 0, 200));
        assertThat(metadata("reference", 100, 100, 200).systemActor())
                .isEqualTo(SystemActor.RETENTION_ENGINE);
    }

    @Test
    void rejectsUnrepresentableDurationsAndBatchLongerThanTick() {
        for (Duration invalid :
                new Duration[] {
                    Duration.ZERO,
                    Duration.ofMillis(-1),
                    Duration.ofNanos(1),
                    Duration.ofMillis(1).plusNanos(1),
                    Duration.ofMillis(Long.MAX_VALUE).plusNanos(1),
                    Duration.ofMillis(Long.MAX_VALUE).plusMillis(1),
                    Duration.ofSeconds(Long.MAX_VALUE)
                }) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> withBatchDuration(invalid))
                    .withMessageContaining("Sweep maximumBatchDuration");
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> withBatchDuration(Duration.ofSeconds(6)));
        assertThatIllegalArgumentException().isThrownBy(() -> new SweepBatchResult(-1, false));
    }

    @Test
    void acceptsPositiveWholeMillisecondsAtRepresentableBoundaries() {
        assertThat(withBatchDuration(Duration.ofMillis(1)).maximumBatchDuration())
                .isEqualTo(Duration.ofMillis(1));
        assertThat(metadata("reference", Long.MAX_VALUE, 1, Long.MAX_VALUE).defaultInterval())
                .isEqualTo(Duration.ofMillis(Long.MAX_VALUE));
    }

    @Test
    void stopBeforeSubscriptionDoesNotStartABatch() {
        var sweep = new ContractSweep();
        var stop = new AtomicBoolean();
        Mono<SweepBatchResult> work = sweep.runBatch(stop::get);
        assertThat(sweep.started).hasValue(0);
        stop.set(true);
        StepVerifier.create(work).expectNext(new SweepBatchResult(0, false)).verifyComplete();
        assertThat(sweep.started).hasValue(0);
    }

    @Test
    void stopDuringBatchAllowsCommitButPreventsAnotherBatch() {
        var sweep = new ContractSweep();
        var stop = new AtomicBoolean();
        StepVerifier.create(sweep.runBatch(stop::get))
                .then(() -> stop.set(true))
                .then(() -> sweep.boundary.tryEmitEmpty())
                .expectNext(new SweepBatchResult(1, true))
                .verifyComplete();
        assertThat(sweep.committed).hasValue(1);
        StepVerifier.create(sweep.runBatch(stop::get))
                .expectNext(new SweepBatchResult(0, false))
                .verifyComplete();
        assertThat(sweep.started).hasValue(1);
    }

    @Test
    void cancellationRollsBackUnfinishedBatch() {
        var sweep = new ContractSweep();
        StepVerifier.create(sweep.runBatch(() -> false)).thenCancel().verify();
        assertThat(sweep.rolledBack).hasValue(1);
        assertThat(sweep.committed).hasValue(0);
    }

    private static SweepMetadata metadata(String name, long interval, long min, long max) {
        return new SweepMetadata(
                name,
                "platform",
                "FEAT-PLAT-006",
                SystemActor.RETENTION_ENGINE,
                Duration.ofMillis(interval),
                Duration.ofMillis(min),
                Duration.ofMillis(max),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5));
    }

    private static SweepMetadata withBatchDuration(Duration duration) {
        return new SweepMetadata(
                "reference",
                "platform",
                "FEAT-PLAT-006",
                SystemActor.RETENTION_ENGINE,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                duration,
                Duration.ofSeconds(5));
    }

    // Contract-only transaction fixture; the PostgreSQL reference sweep belongs to P4.26.
    private static final class ContractSweep implements SweepDefinition {
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger committed = new AtomicInteger();
        private final AtomicInteger rolledBack = new AtomicInteger();
        private final Sinks.Empty<Void> boundary = Sinks.empty();

        @Override
        public SweepMetadata metadata() {
            return SweepDefinitionTest.metadata("reference", 100, 100, 100);
        }

        @Override
        public Mono<SweepBatchResult> runBatch(SweepStopSignal stopSignal) {
            return Mono.defer(
                    () -> {
                        if (stopSignal.isStopRequested()) {
                            return Mono.just(new SweepBatchResult(0, false));
                        }
                        return Mono.usingWhen(
                                Mono.fromSupplier(started::incrementAndGet),
                                ignored ->
                                        boundary.asMono().thenReturn(new SweepBatchResult(1, true)),
                                ignored -> Mono.fromRunnable(committed::incrementAndGet),
                                (ignored, failure) ->
                                        Mono.fromRunnable(rolledBack::incrementAndGet),
                                ignored -> Mono.fromRunnable(rolledBack::incrementAndGet));
                    });
        }
    }
}
