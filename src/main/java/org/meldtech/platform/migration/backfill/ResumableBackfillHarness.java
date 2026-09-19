package org.meldtech.platform.migration.backfill;

import java.util.Objects;
import reactor.core.publisher.Mono;

public final class ResumableBackfillHarness<K> {

    private final BackfillRepository<K> repository;
    private final BackfillRunPermit runPermit;
    private final BackfillThrottle throttle;

    public ResumableBackfillHarness(
            BackfillRepository<K> repository,
            BackfillRunPermit runPermit,
            BackfillThrottle throttle) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.runPermit = Objects.requireNonNull(runPermit, "runPermit");
        this.throttle = Objects.requireNonNull(throttle, "throttle");
    }

    public Mono<BackfillRunResult> run(BackfillDefinition definition) {
        return repository
                .loadOrCreate(definition)
                .switchIfEmpty(
                        Mono.error(
                                new IllegalStateException(
                                        "Backfill checkpoint initialization returned empty")))
                .flatMap(checkpoint -> resume(definition, validate(definition, checkpoint)));
    }

    private Mono<BackfillRunResult> resume(
            BackfillDefinition definition, BackfillCheckpoint<K> checkpoint) {
        return Mono.defer(runPermit::mayProcessNextBatch)
                .switchIfEmpty(Mono.just(false))
                .flatMap(
                        permitted ->
                                permitted
                                        ? process(definition, checkpoint)
                                        : Mono.just(
                                                new BackfillRunResult(
                                                        BackfillRunResult.Status.PAUSED,
                                                        checkpoint.processedRows())));
    }

    private Mono<BackfillRunResult> process(
            BackfillDefinition definition, BackfillCheckpoint<K> checkpoint) {
        return repository
                .processNextBatch(definition, checkpoint)
                .flatMap(
                        batch -> {
                            BackfillCheckpoint<K> next = validate(definition, batch.checkpoint());
                            if (batch.selectedRows() == 0) {
                                return verifyCompletion(definition, next);
                            }
                            return throttle.afterCommittedBatch(
                                            batch.selectedRows(), definition.rowsPerSecond())
                                    .then(Mono.defer(() -> resume(definition, next)));
                        });
    }

    private Mono<BackfillRunResult> verifyCompletion(
            BackfillDefinition definition, BackfillCheckpoint<K> checkpoint) {
        return repository
                .hasEligibleRowsAtOrBelow(definition, checkpoint)
                .flatMap(
                        remaining ->
                                remaining
                                        ? Mono.error(
                                                new IllegalStateException(
                                                        "Backfill returned an empty batch while eligible rows remain"))
                                        : Mono.just(
                                                new BackfillRunResult(
                                                        BackfillRunResult.Status.COMPLETED,
                                                        checkpoint.processedRows())));
    }

    private static <K> BackfillCheckpoint<K> validate(
            BackfillDefinition definition, BackfillCheckpoint<K> checkpoint) {
        if (!definition.definitionChecksum().equals(checkpoint.definitionChecksum())) {
            throw new IllegalStateException(
                    "Backfill definition checksum changed after the run started");
        }
        return checkpoint;
    }
}
