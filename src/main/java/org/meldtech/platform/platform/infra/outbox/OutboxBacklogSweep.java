package org.meldtech.platform.platform.infra.outbox;

import java.time.Duration;
import java.util.function.LongSupplier;
import org.meldtech.platform.platform.api.RoleScoped;
import org.meldtech.platform.platform.api.SweepBatchResult;
import org.meldtech.platform.platform.api.SweepDataModule;
import org.meldtech.platform.platform.api.SweepDefinition;
import org.meldtech.platform.platform.api.SweepMetadata;
import org.meldtech.platform.platform.api.SweepStopSignal;
import org.meldtech.platform.platform.api.SweepTransactions;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import reactor.core.publisher.Mono;

@RoleScoped("worker")
final class OutboxBacklogSweep implements SweepDefinition {

    private final SweepTransactions transactions;
    private final DatabaseOutboxBacklogReader reader;
    private final OutboxBacklogTelemetry telemetry;
    private final OutboxBacklogProperties properties;
    private final LongSupplier monotonicTime;

    OutboxBacklogSweep(
            SweepTransactions transactions,
            DatabaseOutboxBacklogReader reader,
            OutboxBacklogTelemetry telemetry,
            OutboxBacklogProperties properties,
            LongSupplier monotonicTime) {
        this.transactions = transactions;
        this.reader = reader;
        this.telemetry = telemetry;
        this.properties = properties;
        this.monotonicTime = monotonicTime;
    }

    @Override
    public SweepMetadata metadata() {
        return new SweepMetadata(
                "outbox-backlog",
                "outbox",
                "FEAT-PLAT-004",
                SystemActor.OUTBOX_RELAY,
                properties.interval(),
                Duration.ofSeconds(1),
                Duration.ofDays(1),
                Duration.ofSeconds(4),
                Duration.ofSeconds(4));
    }

    @Override
    public Mono<SweepBatchResult> runBatch(SweepStopSignal stopSignal) {
        return Mono.defer(
                () -> {
                    if (stopSignal.isStopRequested()) {
                        return Mono.just(new SweepBatchResult(0, false));
                    }
                    long started = monotonicTime.getAsLong();
                    return transactions
                            .inPlatformTransaction(
                                    SweepDataModule.OUTBOX,
                                    connection ->
                                            reader.read(connection, properties.queryTimeout()))
                            .timeout(properties.queryTimeout())
                            .doOnNext(snapshot -> telemetry.updateBacklog(snapshot, started))
                            .doOnError(failure -> telemetry.backlogCollectionFailed())
                            .doOnCancel(telemetry::backlogCollectionFailed)
                            .thenReturn(new SweepBatchResult(0, false));
                });
    }
}
