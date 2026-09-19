package org.meldtech.platform.migration.backfill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ResumableBackfillHarnessTest {

    private static final BackfillDefinition DEFINITION =
            new BackfillDefinition("answer-normalization", 1, "delivery", "sha256:test", 2, 100);

    @Test
    void restartResumesAtTheCommittedCursorWithoutDuplicateWork() {
        var repository = new InMemoryRepository(List.of(1L, 2L, 3L, 4L, 5L));
        var permits = new AtomicInteger();
        var interrupted =
                new ResumableBackfillHarness<>(
                        repository,
                        () -> Mono.just(permits.getAndIncrement() == 0),
                        (rows, rate) -> Mono.empty());

        StepVerifier.create(interrupted.run(DEFINITION))
                .expectNext(new BackfillRunResult(BackfillRunResult.Status.PAUSED, 2))
                .verifyComplete();

        var resumed =
                new ResumableBackfillHarness<Long>(
                        repository, () -> Mono.just(true), (rows, rate) -> Mono.empty());
        StepVerifier.create(resumed.run(DEFINITION))
                .expectNext(new BackfillRunResult(BackfillRunResult.Status.COMPLETED, 5))
                .verifyComplete();

        assertEquals(Map.of(1L, 1, 2L, 1, 3L, 1, 4L, 1, 5L, 1), repository.updateCounts);
    }

    private static final class InMemoryRepository implements BackfillRepository<Long> {

        private final List<Long> keys;
        private final Map<Long, String> targets = new HashMap<>();
        private final Map<Long, Integer> updateCounts = new HashMap<>();
        private Optional<BackfillCheckpoint<Long>> checkpoint = Optional.empty();

        private InMemoryRepository(List<Long> keys) {
            this.keys = List.copyOf(keys);
        }

        @Override
        public Mono<BackfillCheckpoint<Long>> loadOrCreate(BackfillDefinition definition) {
            if (checkpoint.isEmpty()) {
                checkpoint =
                        Optional.of(
                                new BackfillCheckpoint<>(
                                        definition.definitionChecksum(),
                                        Optional.empty(),
                                        keys.getLast(),
                                        0));
            }
            return Mono.just(checkpoint.orElseThrow());
        }

        @Override
        public Mono<BackfillBatch<Long>> processNextBatch(
                BackfillDefinition definition, BackfillCheckpoint<Long> current) {
            long cursor = current.exclusiveCursor().orElse(0L);
            List<Long> selected =
                    keys.stream()
                            .filter(key -> key > cursor && key <= current.upperBound())
                            .limit(definition.batchSize())
                            .toList();
            for (Long key : selected) {
                if (!targets.containsKey(key)) {
                    targets.put(key, "normalized-" + key);
                    updateCounts.merge(key, 1, Integer::sum);
                }
            }
            if (!selected.isEmpty()) {
                checkpoint =
                        Optional.of(
                                new BackfillCheckpoint<>(
                                        current.definitionChecksum(),
                                        Optional.of(selected.getLast()),
                                        current.upperBound(),
                                        current.processedRows() + selected.size()));
            }
            return Mono.just(
                    new BackfillBatch<>(
                            checkpoint.orElseThrow(), selected.size(), selected.size()));
        }

        @Override
        public Mono<Boolean> hasEligibleRowsAtOrBelow(
                BackfillDefinition definition, BackfillCheckpoint<Long> current) {
            return Mono.just(
                    keys.stream()
                            .filter(key -> key <= current.upperBound())
                            .anyMatch(key -> !targets.containsKey(key)));
        }
    }
}
