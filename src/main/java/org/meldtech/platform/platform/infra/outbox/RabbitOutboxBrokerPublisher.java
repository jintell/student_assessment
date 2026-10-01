package org.meldtech.platform.platform.infra.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

final class RabbitOutboxBrokerPublisher implements OutboxBrokerPublisher {

    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);
    private final RabbitTemplate rabbitTemplate;

    RabbitOutboxBrokerPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
    }

    @Override
    public Mono<BrokerConfirmation> publish(ClaimedOutboxEvent event) {
        return Mono.defer(
                        () -> {
                            CorrelationData correlation = new CorrelationData(event.eventId());
                            rabbitTemplate.send(
                                    IntegrationTopologyConfiguration.INTEGRATION_EXCHANGE,
                                    event.eventType().toString(),
                                    message(event),
                                    correlation);
                            return Mono.fromFuture(correlation.getFuture())
                                    .map(confirm -> new BrokerConfirmation(confirm.ack()));
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(CONFIRM_TIMEOUT);
    }

    private static Message message(ClaimedOutboxEvent event) {
        MessageBuilder builder =
                MessageBuilder.withBody(event.payload().getBytes(StandardCharsets.UTF_8));
        builder.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        builder.setMessageId(event.eventId());
        builder.setHeader("event_type", event.eventType().toString());
        builder.setHeader("tenant_id", event.tenantId());
        builder.setHeader("aggregate_type", event.aggregateType());
        builder.setHeader("aggregate_id", event.aggregateId());
        builder.setHeader("correlation_id", event.correlationId());
        builder.setHeader("occurred_at", event.occurredAt().toString());
        event.traceparent().ifPresent(value -> builder.setHeader("traceparent", value));
        event.tracestate().ifPresent(value -> builder.setHeader("tracestate", value));
        return builder.build();
    }
}
