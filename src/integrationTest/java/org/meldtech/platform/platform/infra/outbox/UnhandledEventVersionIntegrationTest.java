package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.RabbitMqTestContainer;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class UnhandledEventVersionIntegrationTest {

    @Test
    void unknownVersionIsDeadLetteredAndAlertedButNeverReportedHandled() {
        RabbitMQContainer rabbitMq = RabbitMqTestContainer.newInstance();
        rabbitMq.start();
        CachingConnectionFactory connectionFactory =
                new CachingConnectionFactory(rabbitMq.getHost(), rabbitMq.getAmqpPort());
        connectionFactory.setUsername(rabbitMq.getAdminUsername());
        connectionFactory.setPassword(rabbitMq.getAdminPassword());
        try {
            RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
            declareTopology(rabbitTemplate);
            List<String> alerts = new ArrayList<>();
            VersionAwareEventDispatcher dispatcher =
                    new VersionAwareEventDispatcher(
                            List.of(),
                            new RabbitDeadLetterPublisher(rabbitTemplate),
                            (eventId, reason) ->
                                    Mono.fromRunnable(
                                            () -> alerts.add(eventId + ":" + reason.name())));
            ConsumerEnvelope envelope =
                    new ConsumerEnvelope(
                            new ConsumedEvent(
                                    "01950f47-6000-7005-8000-000000000001",
                                    "01950f47-6000-7005-8000-000000000002",
                                    EventType.parse("platform.ReferenceEvent.v2")),
                            "reference-1",
                            "{\"revision\":2}",
                            "01ARZ3NDEKTSV4RRFFQ69G5FAV");

            StepVerifier.create(dispatcher.dispatch(envelope))
                    .expectNext(
                            VersionAwareEventDispatcher.DispatchResult
                                    .DEAD_LETTERED_UNHANDLED_VERSION)
                    .verifyComplete();

            var received =
                    rabbitTemplate.receive(
                            IntegrationTopologyConfiguration.DEAD_LETTER_QUEUE, 5_000);
            assertThat(received).isNotNull();
            var deadLetter = Objects.requireNonNull(received);
            assertThat(deadLetter.getMessageProperties().getMessageId())
                    .isEqualTo(envelope.event().eventId());
            assertThat(deadLetter.getMessageProperties().getHeaders())
                    .containsEntry("dead_letter_reason", "UNHANDLED_EVENT_VERSION")
                    .doesNotContainEntry("dead_letter_reason", "POISON_PAYLOAD");
            assertThat(alerts)
                    .containsExactly(envelope.event().eventId() + ":UNHANDLED_EVENT_VERSION");
        } finally {
            connectionFactory.destroy();
            rabbitMq.stop();
        }
    }

    private static void declareTopology(RabbitTemplate rabbitTemplate) {
        Declarables topology = new IntegrationTopologyConfiguration().integrationTopology();
        RabbitAdmin admin = new RabbitAdmin(rabbitTemplate);
        topology.getDeclarablesByType(TopicExchange.class).forEach(admin::declareExchange);
        topology.getDeclarablesByType(Queue.class).forEach(admin::declareQueue);
        topology.getDeclarablesByType(Binding.class).forEach(admin::declareBinding);
    }
}
