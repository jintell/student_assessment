package org.meldtech.platform.runtimefixture;

import io.r2dbc.spi.Row;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import org.meldtech.platform.platform.api.RoleScoped;
import org.meldtech.platform.platform.api.SweepBatchResult;
import org.meldtech.platform.platform.api.SweepConnection;
import org.meldtech.platform.platform.api.SweepDataModule;
import org.meldtech.platform.platform.api.SweepDefinition;
import org.meldtech.platform.platform.api.SweepMetadata;
import org.meldtech.platform.platform.api.SweepStopSignal;
import org.meldtech.platform.platform.api.SweepTransactions;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Test-only exemplar: progress is durable, and one subscription owns one transaction. */
@RoleScoped("worker")
public final class ReferenceSweep implements SweepDefinition {
    public static final String NAME = "reference-sweep";
    private static final SweepMetadata METADATA =
            new SweepMetadata(
                    NAME,
                    "delivery",
                    "FEAT-PLAT-006",
                    SystemActor.AUTO_SUBMIT_SWEEPER,
                    Duration.ofMillis(100),
                    Duration.ofMillis(100),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(4),
                    Duration.ofSeconds(5));
    private static final String COMPLETE_ONE =
            """
            UPDATE delivery.reference_sweep_work SET completed = true
            WHERE id = (SELECT id FROM delivery.reference_sweep_work
                        WHERE NOT completed ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED)
            RETURNING id, current_user AS database_role,
                      current_setting('app.platform_scope', false) AS platform_scope
            """;

    private final SweepTransactions transactions;
    private final Function<Observation, Mono<Void>> beforeCommit;

    public ReferenceSweep(
            SweepTransactions transactions, Function<Observation, Mono<Void>> beforeCommit) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.beforeCommit = Objects.requireNonNull(beforeCommit, "beforeCommit");
    }

    @Override
    public SweepMetadata metadata() {
        return METADATA;
    }

    @Override
    public Mono<SweepBatchResult> runBatch(SweepStopSignal stopSignal) {
        return Mono.defer(
                () -> {
                    if (stopSignal.isStopRequested()) {
                        return Mono.just(new SweepBatchResult(0, false));
                    }
                    return transactions.inPlatformTransaction(
                            SweepDataModule.DELIVERY, this::completeOne);
                });
    }

    private Mono<SweepBatchResult> completeOne(SweepConnection connection) {
        return Mono.deferContextual(
                context -> {
                    ActorContext actor = context.get(ActorContext.class);
                    return Flux.from(connection.createStatement(COMPLETE_ONE).execute())
                            .concatMap(result -> result.map((row, metadata) -> observe(row, actor)))
                            .singleOrEmpty()
                            // Pause or fail inside the owned transaction to exercise interruption.
                            .flatMap(
                                    observation ->
                                            beforeCommit
                                                    .apply(observation)
                                                    .thenReturn(new SweepBatchResult(1, true)))
                            .defaultIfEmpty(new SweepBatchResult(0, false));
                });
    }

    private static Observation observe(Row row, ActorContext actor) {
        return new Observation(
                Objects.requireNonNull(row.get("id", Integer.class)),
                Objects.requireNonNull(row.get("database_role", String.class)),
                Objects.requireNonNull(row.get("platform_scope", String.class)),
                actor);
    }

    public record Observation(
            int id, String databaseRole, String platformScope, ActorContext actor) {}
}
