package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.RabbitMqTestContainer;
import org.meldtech.platform.platform.infra.observability.TraceContextContinuation;
import org.meldtech.platform.shared.infra.web.RequestContextWebFilterHarness;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.OutboxEventId;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.meldtech.platform.shared.kernel.outbox.AggregateReference;
import org.meldtech.platform.shared.kernel.outbox.OutboxMessage;
import org.meldtech.platform.testing.observability.ObservabilityTestFixture;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class ObservabilityPropagationIntegrationTest extends OutboxPostgreSqlIntegrationTestSupport {

    private static final String QUEUE = "integration.observability-propagation";
    private static final String EVENT_ID = "01950f47-6000-7001-8000-000000000101";
    private static final String SECOND_EVENT_ID = "01950f47-6000-7001-8000-000000000102";
    private static final UUID TENANT_VALUE =
            UUID.fromString("01950f47-6000-7000-8000-000000000001");
    private static final TenantId TENANT = TenantId.parse(TENANT_VALUE.toString());
    private static final CorrelationId CORRELATION =
            CorrelationId.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    private static final ActorContext ACTOR =
            ActorContext.tenantWorkforce(
                    new ActorId("observability-propagation"),
                    TENANT,
                    CORRELATION,
                    SourceIp.parse("127.0.0.1"));
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final TextMapSetter<Map<String, String>> MAP_SETTER = Map::put;

    @Test
    void propagatesOneCorrelationIdentifierAndTraceAcrossHttpOutboxBrokerAndConsumer()
            throws Exception {
        RabbitMQContainer rabbitMq = RabbitMqTestContainer.newInstance();
        rabbitMq.start();
        try (ObservabilityTestFixture telemetry =
                ObservabilityTestFixture.create(ObservabilityPropagationIntegrationTest.class)) {
            CachingConnectionFactory connectionFactory = connectionFactory(rabbitMq);
            try {
                RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
                declareTopology(rabbitTemplate);
                Span requestSpan =
                        telemetry.tracer().spanBuilder("platform.httpRequest").startSpan();
                Map<String, String> traceCarrier = traceCarrier(requestSpan);

                StepVerifier.create(
                                RequestContextWebFilterHarness.filter(
                                        CORRELATION.toString(),
                                        ACTOR,
                                        () -> commitEvent(traceCarrier)))
                        .expectNext(CORRELATION.toString())
                        .verifyComplete();
                requestSpan.end();

                ClaimedOutboxEvent claimed = claimEvent();
                assertThat(claimed.correlationId()).isEqualTo(CORRELATION.toString());
                assertThat(claimed.traceparent()).contains(traceCarrier.get("traceparent"));

                RelayPublishStep relay =
                        new RelayPublishStep(
                                new RabbitOutboxBrokerPublisher(rabbitTemplate),
                                new DatabaseOutboxPublicationStore(databaseClient),
                                (eventId, reason) -> Mono.empty(),
                                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC),
                                mock(OutboxTelemetry.class));
                StepVerifier.create(relay.publishOne(claimed)).verifyComplete();

                Message message = Objects.requireNonNull(rabbitTemplate.receive(QUEUE, 5_000));
                Map<String, Object> headers =
                        new HashMap<>(message.getMessageProperties().getHeaders());
                assertThat(headers)
                        .containsEntry("correlation_id", CORRELATION.toString())
                        .containsEntry("traceparent", traceCarrier.get("traceparent"));

                TraceContextContinuation.StartedSpan consumer =
                        new TraceContextContinuation(
                                        telemetry.openTelemetry(),
                                        () -> NOW.plusSeconds(1),
                                        Duration.ofHours(1))
                                .continueFromMessageHeaders("platform.consumeReference", headers);
                consumer.span().end();

                var spans = telemetry.finishedSpans();
                assertThat(consumer.decision())
                        .isEqualTo(TraceContextContinuation.ContinuationDecision.CHILD);
                assertThat(spans).hasSize(2);
                assertThat(spans.get(1).getTraceId()).isEqualTo(spans.getFirst().getTraceId());
                assertThat(spans.get(1).getParentSpanId()).isEqualTo(spans.getFirst().getSpanId());
            } finally {
                connectionFactory.destroy();
            }
        } finally {
            rabbitMq.stop();
        }
    }

    @Test
    void preservesCorrelationAndTraceAcrossAReactorSchedulerHop() {
        try (ObservabilityTestFixture telemetry =
                ObservabilityTestFixture.create(ObservabilityPropagationIntegrationTest.class)) {
            Span requestSpan = telemetry.tracer().spanBuilder("platform.httpRequest").startSpan();
            Map<String, String> traceCarrier = traceCarrier(requestSpan);

            StepVerifier.create(
                            RequestContextWebFilterHarness.filter(
                                    CORRELATION.toString(),
                                    ACTOR,
                                    () ->
                                            Mono.delay(Duration.ofMillis(1), Schedulers.parallel())
                                                    .then(commitEvent(traceCarrier))))
                    .expectNext(CORRELATION.toString())
                    .verifyComplete();
            requestSpan.end();

            ClaimedOutboxEvent claimed = claimEvent();
            assertThat(claimed.correlationId()).isEqualTo(CORRELATION.toString());
            assertThat(claimed.traceparent()).contains(traceCarrier.get("traceparent"));
        }
    }

    @Test
    void preservesCorrelationAndTraceForEveryEventInARelayBatch() throws Exception {
        RabbitMQContainer rabbitMq = RabbitMqTestContainer.newInstance();
        rabbitMq.start();
        try (ObservabilityTestFixture telemetry =
                ObservabilityTestFixture.create(ObservabilityPropagationIntegrationTest.class)) {
            CachingConnectionFactory connectionFactory = connectionFactory(rabbitMq);
            try {
                RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
                declareTopology(rabbitTemplate);
                Span requestSpan =
                        telemetry.tracer().spanBuilder("platform.httpBatchRequest").startSpan();
                Map<String, String> traceCarrier = traceCarrier(requestSpan);

                StepVerifier.create(
                                RequestContextWebFilterHarness.filter(
                                        CORRELATION.toString(),
                                        ACTOR,
                                        () ->
                                                commitEvent(traceCarrier)
                                                        .then(
                                                                commitEvent(
                                                                        traceCarrier,
                                                                        SECOND_EVENT_ID,
                                                                        2))))
                        .expectNext(CORRELATION.toString())
                        .verifyComplete();
                requestSpan.end();

                List<ClaimedOutboxEvent> claimed = claimEvents(2);
                assertThat(claimed).hasSize(2);
                RelayPublishStep relay =
                        new RelayPublishStep(
                                new RabbitOutboxBrokerPublisher(rabbitTemplate),
                                new DatabaseOutboxPublicationStore(databaseClient),
                                (eventId, reason) -> Mono.empty(),
                                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC),
                                mock(OutboxTelemetry.class));
                StepVerifier.create(
                                relay.publishSequentially(
                                        reactor.core.publisher.Flux.fromIterable(claimed)))
                        .verifyComplete();

                for (int index = 0; index < 2; index++) {
                    Message message = Objects.requireNonNull(rabbitTemplate.receive(QUEUE, 5_000));
                    assertThat(message.getMessageProperties().getHeaders())
                            .containsEntry("correlation_id", CORRELATION.toString())
                            .containsEntry("traceparent", traceCarrier.get("traceparent"));
                }
            } finally {
                connectionFactory.destroy();
            }
        } finally {
            rabbitMq.stop();
        }
    }

    private Mono<Void> commitEvent(Map<String, String> traceCarrier) {
        return commitEvent(traceCarrier, EVENT_ID, 1);
    }

    private Mono<Void> commitEvent(Map<String, String> traceCarrier, String eventId, int revision) {
        ReactiveOutboxWriter writer =
                new ReactiveOutboxWriter(
                        databaseClient,
                        new RegisteredEventSchemaValidator(
                                Path.of("contracts/events"), new ObjectMapper()),
                        new OutboxContextCarrier(),
                        mock(OutboxTelemetry.class));
        Mono<Void> work =
                installModuleContext(TENANT_VALUE)
                        .then(
                                Mono.from(
                                        writer.append(
                                                TENANT,
                                                ACTOR,
                                                new OutboxMessage(
                                                        OutboxEventId.parse(eventId),
                                                        "platform.ReferenceEvent.v1",
                                                        new AggregateReference(
                                                                "Reference", eventId),
                                                        new ReferenceEvent(
                                                                "platform.ReferenceEvent.v1",
                                                                eventId,
                                                                revision),
                                                        CORRELATION,
                                                        NOW))));
        return transactions
                .transactional(work)
                .contextWrite(
                        context ->
                                context.put(
                                                OutboxContextCarrier.TRACEPARENT_KEY,
                                                Objects.requireNonNull(
                                                        traceCarrier.get("traceparent")))
                                        .put(
                                                OutboxContextCarrier.TRACESTATE_KEY,
                                                traceCarrier.getOrDefault(
                                                        "tracestate", "test=value")));
    }

    private ClaimedOutboxEvent claimEvent() {
        return claimEvents(1).getFirst();
    }

    private List<ClaimedOutboxEvent> claimEvents(int batchSize) {
        Mono<List<ClaimedOutboxEvent>> claim =
                databaseClient
                        .sql("SET LOCAL ROLE app_outbox_relay")
                        .fetch()
                        .rowsUpdated()
                        .then(
                                databaseClient
                                        .sql(
                                                "SELECT set_config('app.platform_scope', "
                                                        + "'outbox_relay', true)")
                                        .fetch()
                                        .rowsUpdated())
                        .thenMany(
                                new OutboxClaimRepository(databaseClient)
                                        .claim(
                                                batchSize,
                                                "observability-relay",
                                                NOW.plusSeconds(30)))
                        .collectList();
        return Objects.requireNonNull(transactions.transactional(claim).block());
    }

    private static Map<String, String> traceCarrier(Span requestSpan) {
        Map<String, String> carrier = new HashMap<>();
        W3CTraceContextPropagator.getInstance()
                .inject(Context.root().with(requestSpan), carrier, MAP_SETTER);
        return Map.copyOf(carrier);
    }

    private static CachingConnectionFactory connectionFactory(RabbitMQContainer rabbitMq) {
        CachingConnectionFactory connectionFactory =
                new CachingConnectionFactory(rabbitMq.getHost(), rabbitMq.getAmqpPort());
        connectionFactory.setUsername(rabbitMq.getAdminUsername());
        connectionFactory.setPassword(rabbitMq.getAdminPassword());
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        return connectionFactory;
    }

    private static void declareTopology(RabbitTemplate rabbitTemplate) {
        RabbitAdmin admin = new RabbitAdmin(rabbitTemplate);
        TopicExchange exchange =
                new TopicExchange(
                        IntegrationTopologyConfiguration.INTEGRATION_EXCHANGE, true, false);
        Queue queue = QueueBuilder.durable(QUEUE).quorum().build();
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("platform.#"));
    }
}
