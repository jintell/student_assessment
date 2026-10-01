package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.RabbitMqTestContainer;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.testcontainers.rabbitmq.RabbitMQContainer;

class IntegrationTopologyBrokerIntegrationTest {

    private static RabbitMQContainer rabbitMq;
    private static CachingConnectionFactory connectionFactory;

    @BeforeAll
    static void startBroker() {
        rabbitMq = RabbitMqTestContainer.instance();
        rabbitMq.start();
        connectionFactory =
                new CachingConnectionFactory(rabbitMq.getHost(), rabbitMq.getAmqpPort());
        connectionFactory.setUsername(rabbitMq.getAdminUsername());
        connectionFactory.setPassword(rabbitMq.getAdminPassword());
    }

    @AfterAll
    static void closeConnectionFactory() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void redeclaresRealQuorumTopologyWithoutChangingItsShape() throws Exception {
        Declarables topology = new IntegrationTopologyConfiguration().integrationTopology();
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);

        declare(admin, topology);
        declare(admin, topology);

        var queues =
                rabbitMq.execInContainer(
                        "rabbitmqctl",
                        "list_queues",
                        "--formatter",
                        "json",
                        "name",
                        "type",
                        "arguments");
        assertThat(queues.getExitCode()).isZero();
        assertThat(queues.getStdout()).contains("integration.dlq", "quorum");
        for (String boundedContext : IntegrationTopologyConfiguration.CONTEXTS) {
            assertThat(queues.getStdout()).contains("integration." + boundedContext);
        }
    }

    private static void declare(RabbitAdmin admin, Declarables topology) {
        topology.getDeclarablesByType(TopicExchange.class).forEach(admin::declareExchange);
        topology.getDeclarablesByType(Queue.class).forEach(admin::declareQueue);
        topology.getDeclarablesByType(Binding.class).forEach(admin::declareBinding);
    }
}
