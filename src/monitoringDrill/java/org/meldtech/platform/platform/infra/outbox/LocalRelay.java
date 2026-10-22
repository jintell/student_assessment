package org.meldtech.platform.platform.infra.outbox;

import io.r2dbc.spi.ConnectionFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.meldtech.platform.platform.api.RoleScoped;
import org.meldtech.platform.platform.api.SweepBatchResult;
import org.meldtech.platform.platform.api.SweepDefinition;
import org.meldtech.platform.platform.api.SweepMetadata;
import org.meldtech.platform.platform.api.SweepStopSignal;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RoleScoped("worker")
final class LocalRelay implements SweepDefinition, DisposableBean {
    private final LocalRelayControl control;
    private final CachingConnectionFactory broker;
    private final TransactionalOperator transactions;
    private final OutboxClaimRepository claims;
    private final RelayPublishStep publisher;

    LocalRelay(
            @Qualifier("workerConnectionFactory") ConnectionFactory connections,
            LocalRelayControl control,
            MicrometerOutboxTelemetry telemetry,
            Environment environment) {
        this.control = control;
        broker = new CachingConnectionFactory("rabbitmq");
        broker.setUsername("p722");
        broker.setPassword(environment.getRequiredProperty("drill.broker-password"));
        broker.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        var template = new RabbitTemplate(broker);
        var admin = new RabbitAdmin(template);
        var exchange = new TopicExchange(IntegrationTopologyConfiguration.INTEGRATION_EXCHANGE);
        var queue = new Queue("p722-delivered", true);
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(
                BindingBuilder.bind(queue).to(exchange).with("platform.ReferenceEvent.v1"));
        var client = DatabaseClient.create(connections);
        transactions = TransactionalOperator.create(new R2dbcTransactionManager(connections));
        claims = new OutboxClaimRepository(client);
        var store = new DatabaseOutboxPublicationStore(client);
        OutboxPublicationStore transactionalStore =
                new OutboxPublicationStore() {
                    @Override
                    public Mono<Void> markPublished(ClaimedOutboxEvent event, Instant publishedAt) {
                        return transactions.transactional(store.markPublished(event, publishedAt));
                    }

                    @Override
                    public Mono<FailureTransition> recordFailure(
                            ClaimedOutboxEvent event,
                            PublicationFailureReason reason,
                            Instant nextAttemptAt) {
                        return transactions.transactional(
                                store.recordFailure(event, reason, nextAttemptAt));
                    }
                };
        publisher =
                new RelayPublishStep(
                        new RabbitOutboxBrokerPublisher(template),
                        transactionalStore,
                        (eventId, reason) ->
                                Mono.error(new IllegalStateException("Local relay failed")),
                        java.time.Clock.systemUTC(),
                        telemetry,
                        io.opentelemetry.api.OpenTelemetry.noop().getTracer("local-monitoring"));
    }

    @Override
    public SweepMetadata metadata() {
        return new SweepMetadata(
                "outbox-relay",
                "outbox",
                "FEAT-PLAT-004",
                SystemActor.OUTBOX_RELAY,
                Duration.ofMillis(200),
                Duration.ofMillis(200),
                Duration.ofMillis(200),
                Duration.ofSeconds(4),
                Duration.ofSeconds(4));
    }

    @Override
    public Mono<SweepBatchResult> runBatch(SweepStopSignal stopSignal) {
        return Mono.deferContextual(
                context -> {
                    if (control.isHeld() || stopSignal.isStopRequested()) {
                        return Mono.just(new SweepBatchResult(0, false));
                    }
                    Mono<List<ClaimedOutboxEvent>> claimed =
                            transactions.transactional(
                                    claims.claim(
                                                    25,
                                                    "local-monitoring",
                                                    Instant.now().plusSeconds(30))
                                            .collectList());
                    return new RelaySecurityContext()
                            .install(
                                    context.get(ActorContext.class),
                                    claimed.flatMap(
                                            events ->
                                                    publisher
                                                            .publishSequentially(
                                                                    Flux.fromIterable(events))
                                                            .thenReturn(
                                                                    new SweepBatchResult(
                                                                            events.size(),
                                                                            false))));
                });
    }

    @Override
    public void destroy() {
        broker.destroy();
    }
}
