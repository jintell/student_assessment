package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import reactor.test.StepVerifier;

class RabbitPublishersTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-03T12:00:00Z");

    @Test
    void publishesOutboxEnvelopeAndReturnsBrokerConfirmation() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        doAnswer(
                        invocation -> {
                            CorrelationData correlation = invocation.getArgument(3);
                            correlation
                                    .getFuture()
                                    .complete(new CorrelationData.Confirm(true, null));
                            return null;
                        })
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        StepVerifier.create(new RabbitOutboxBrokerPublisher(rabbitTemplate).publish(outboxEvent()))
                .assertNext(confirmation -> assertThat(confirmation.acknowledged()).isTrue())
                .verifyComplete();

        verify(rabbitTemplate)
                .send(
                        org.mockito.ArgumentMatchers.eq(
                                IntegrationTopologyConfiguration.INTEGRATION_EXCHANGE),
                        org.mockito.ArgumentMatchers.eq("platform.ReferenceEvent.v1"),
                        message.capture(),
                        any(CorrelationData.class));
        assertThat(message.getValue().getBody())
                .isEqualTo("{\"revision\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(message.getValue().getMessageProperties().getMessageId())
                .isEqualTo("01950f47-6000-7000-8000-000000000001");
        assertThat(message.getValue().getMessageProperties().getHeaders())
                .containsEntry("tenant_id", "01950f47-6000-7000-8000-000000000002")
                .containsEntry("aggregate_type", "Assessment")
                .containsEntry("aggregate_id", "assessment-1")
                .containsEntry("correlation_id", "01ARZ3NDEKTSV4RRFFQ69G5FAV")
                .containsEntry("traceparent", "00-trace-parent")
                .containsEntry("tracestate", "vendor=value");
    }

    @Test
    void publishesDeadLetterWithDiagnosticHeaders() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        ConsumerEnvelope envelope =
                new ConsumerEnvelope(
                        new ConsumedEvent(
                                "01950f47-6000-7000-8000-000000000001",
                                "01950f47-6000-7000-8000-000000000002",
                                EventType.parse("platform.ReferenceEvent.v1")),
                        "assessment-1",
                        "{\"revision\":1}",
                        "01ARZ3NDEKTSV4RRFFQ69G5FAV");

        StepVerifier.create(
                        new RabbitDeadLetterPublisher(rabbitTemplate)
                                .deadLetter(
                                        envelope,
                                        DeadLetterPublisher.DeadLetterReason.POISON_PAYLOAD))
                .verifyComplete();

        verify(rabbitTemplate)
                .send(
                        org.mockito.ArgumentMatchers.eq(
                                IntegrationTopologyConfiguration.DEAD_LETTER_EXCHANGE),
                        org.mockito.ArgumentMatchers.eq("dead-letter.poison_payload"),
                        message.capture());
        assertThat(message.getValue().getMessageProperties().getHeaders())
                .containsEntry("event_type", "platform.ReferenceEvent.v1")
                .containsEntry("dead_letter_reason", "POISON_PAYLOAD")
                .containsEntry("correlation_id", "01ARZ3NDEKTSV4RRFFQ69G5FAV");
    }

    private static ClaimedOutboxEvent outboxEvent() {
        return new ClaimedOutboxEvent(
                "01950f47-6000-7000-8000-000000000001",
                "01950f47-6000-7000-8000-000000000002",
                "Assessment",
                "assessment-1",
                EventType.parse("platform.ReferenceEvent.v1"),
                "{\"revision\":1}",
                "01ARZ3NDEKTSV4RRFFQ69G5FAV",
                Optional.of("00-trace-parent"),
                Optional.of("vendor=value"),
                1,
                "relay-a",
                OCCURRED_AT,
                OCCURRED_AT);
    }
}
