package org.meldtech.platform.platform.infra.outbox;

import java.util.Objects;
import java.util.UUID;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.meldtech.platform.shared.kernel.outbox.OutboxMessage;
import org.meldtech.platform.shared.kernel.outbox.OutboxWriter;
import org.reactivestreams.Publisher;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;

final class ReactiveOutboxWriter implements OutboxWriter {

    private static final String INSERT =
            """
            INSERT INTO outbox.outbox_event (
                outbox_event_id, tenant_id, aggregate_type, aggregate_id,
                event_type, payload, correlation_id, traceparent, tracestate, occurred_at
            ) VALUES (
                :eventId, :tenantId, :aggregateType, :aggregateId,
                :eventType, CAST(:payload AS jsonb), :correlationId,
                :traceparent, :tracestate, :occurredAt
            )
            """;

    private final DatabaseClient databaseClient;
    private final RegisteredEventSchemaValidator schemaValidator;
    private final OutboxContextCarrier contextCarrier;
    private final OutboxTelemetry telemetry;

    ReactiveOutboxWriter(
            DatabaseClient databaseClient,
            RegisteredEventSchemaValidator schemaValidator,
            OutboxContextCarrier contextCarrier,
            OutboxTelemetry telemetry) {
        this.databaseClient = Objects.requireNonNull(databaseClient, "databaseClient");
        this.schemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator");
        this.contextCarrier = Objects.requireNonNull(contextCarrier, "contextCarrier");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    @Override
    public Publisher<Void> append(TenantId tenantId, ActorContext actor, OutboxMessage message) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(message, "message");
        return Mono.deferContextual(
                context -> {
                    OutboxContextCarrier.CarriedContext carried =
                            contextCarrier.capture(context, actor);
                    return TransactionSynchronizationManager.forCurrentTransaction()
                            .then(Mono.defer(() -> insert(tenantId, message, carried)));
                });
    }

    private Mono<Void> insert(
            TenantId tenantId, OutboxMessage message, OutboxContextCarrier.CarriedContext carried) {
        EventType eventType = EventType.parse(message.eventType());
        String payload = schemaValidator.validateAndSerialize(eventType, message.payload());
        DatabaseClient.GenericExecuteSpec insert =
                databaseClient
                        .sql(INSERT)
                        .bind("eventId", UUID.fromString(message.eventId().toString()))
                        .bind("tenantId", UUID.fromString(tenantId.toString()))
                        .bind("aggregateType", message.aggregate().aggregateType())
                        .bind("aggregateId", message.aggregate().aggregateId())
                        .bind("eventType", message.eventType())
                        .bind("payload", payload)
                        .bind("correlationId", carried.correlationId())
                        .bind("occurredAt", message.occurredAt());
        insert = bindOptional(insert, "traceparent", carried.traceparent());
        insert = bindOptional(insert, "tracestate", carried.tracestate());
        return insert.fetch()
                .rowsUpdated()
                .flatMap(
                        rows ->
                                rows == 1
                                        ? Mono.<Void>empty()
                                        : Mono.<Void>error(
                                                new IllegalStateException(
                                                        "Outbox insert affected "
                                                                + rows
                                                                + " rows")))
                .doOnSuccess(ignored -> telemetry.writerAppended());
    }

    private static DatabaseClient.GenericExecuteSpec bindOptional(
            DatabaseClient.GenericExecuteSpec statement,
            String name,
            java.util.Optional<String> value) {
        return value.map(text -> statement.bind(name, text))
                .orElseGet(() -> statement.bindNull(name, String.class));
    }
}
