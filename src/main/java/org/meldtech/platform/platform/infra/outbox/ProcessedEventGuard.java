package org.meldtech.platform.platform.infra.outbox;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;

final class ProcessedEventGuard {

    private final DatabaseClient databaseClient;
    private final OutboxTelemetry telemetry;
    private final String reserveSql;
    private final String outcomeSql;

    ProcessedEventGuard(
            ConsumerModule module, DatabaseClient databaseClient, OutboxTelemetry telemetry) {
        String schema = Objects.requireNonNull(module, "module").schema();
        this.databaseClient = Objects.requireNonNull(databaseClient, "databaseClient");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.reserveSql = reserveSql(schema);
        this.outcomeSql = outcomeSql(schema);
    }

    Mono<ProcessedEventOutcome> applyOnce(
            ConsumedEvent event, Supplier<Mono<ProcessedEventOutcome>> effect) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(effect, "effect");
        return TransactionSynchronizationManager.forCurrentTransaction()
                .then(reserve(event))
                .flatMap(
                        reserved -> {
                            if (!reserved) {
                                telemetry.consumerDuplicate();
                                return Mono.just(ProcessedEventOutcome.DELIVERY_DUPLICATE);
                            }
                            return Mono.defer(effect)
                                    .flatMap(outcome -> persistOutcome(event, outcome));
                        });
    }

    private Mono<Boolean> reserve(ConsumedEvent event) {
        return databaseClient
                .sql(reserveSql)
                .bind("eventId", event.eventId())
                .bind("tenantId", event.tenantId())
                .bind("eventType", event.eventType().toString())
                .map((row, metadata) -> true)
                .one()
                .defaultIfEmpty(false);
    }

    private Mono<ProcessedEventOutcome> persistOutcome(
            ConsumedEvent event, ProcessedEventOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == ProcessedEventOutcome.DELIVERY_DUPLICATE) {
            return Mono.error(
                    new IllegalArgumentException(
                            "DELIVERY_DUPLICATE is produced only by the event-id guard"));
        }
        if (outcome == ProcessedEventOutcome.APPLIED) {
            return Mono.just(outcome);
        }
        return databaseClient
                .sql(outcomeSql)
                .bind("outcome", outcome.name())
                .bind("eventId", event.eventId())
                .fetch()
                .rowsUpdated()
                .flatMap(
                        rows ->
                                rows == 1
                                        ? Mono.just(outcome)
                                        : Mono.error(
                                                new IllegalStateException(
                                                        "Processed-event outcome update lost")));
    }

    static String reserveSql(String schema) {
        return """
                INSERT INTO %s.processed_event (
                    outbox_event_id, tenant_id, event_type, processed_at, outcome
                ) VALUES (
                    CAST(:eventId AS uuid), CAST(:tenantId AS uuid), :eventType,
                    CURRENT_TIMESTAMP, 'APPLIED'
                )
                ON CONFLICT (outbox_event_id) DO NOTHING
                RETURNING outbox_event_id
                """
                .formatted(schema);
    }

    static String outcomeSql(String schema) {
        return "UPDATE %s.processed_event SET outcome = :outcome ".formatted(schema)
                + "WHERE outbox_event_id = CAST(:eventId AS uuid)";
    }
}
