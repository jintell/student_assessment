package org.meldtech.platform.platform.infra.outbox;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

final class RabbitDeadLetterPublisher implements DeadLetterPublisher {

    private final RabbitTemplate rabbitTemplate;

    RabbitDeadLetterPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
    }

    @Override
    public Mono<Void> deadLetter(ConsumerEnvelope envelope, DeadLetterReason reason) {
        return Mono.fromRunnable(
                        () ->
                                rabbitTemplate.send(
                                        IntegrationTopologyConfiguration.DEAD_LETTER_EXCHANGE,
                                        "dead-letter."
                                                + reason.name().toLowerCase(java.util.Locale.ROOT),
                                        MessageBuilder.withBody(
                                                        envelope.payload()
                                                                .getBytes(StandardCharsets.UTF_8))
                                                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                                                .setMessageId(envelope.event().eventId())
                                                .setHeader(
                                                        "event_type",
                                                        envelope.event().eventType().toString())
                                                .setHeader("dead_letter_reason", reason.name())
                                                .setHeader(
                                                        "correlation_id", envelope.correlationId())
                                                .build()))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
