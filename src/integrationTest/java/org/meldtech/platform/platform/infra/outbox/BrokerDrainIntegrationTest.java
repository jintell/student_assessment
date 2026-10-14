package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.RabbitMqTestContainer;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

class BrokerDrainIntegrationTest {

    private static CachingConnectionFactory connections;

    @BeforeAll
    static void startBroker() {
        var broker = RabbitMqTestContainer.instance();
        broker.start();
        connections = new CachingConnectionFactory(broker.getHost(), broker.getAmqpPort());
        connections.setUsername(broker.getAdminUsername());
        connections.setPassword(broker.getAdminPassword());
    }

    @AfterAll
    static void closeConnections() {
        if (connections != null) {
            connections.destroy();
        }
    }

    @Test
    void acknowledgesCompletedWorkAndLeavesFailedOrBufferedDeliveriesRecoverable()
            throws Exception {
        for (boolean fail : java.util.List.of(false, true)) {
            String queue = "drain-" + UUID.randomUUID();
            var admin = new RabbitAdmin(connections);
            admin.declareQueue(new Queue(queue, false, false, false));
            var template = new RabbitTemplate(connections);
            template.convertAndSend(queue, "active");
            template.convertAndSend(queue, "buffered");
            var entered = new CountDownLatch(1);
            var finish = new CountDownLatch(1);
            var drained = new CountDownLatch(1);
            var container = new SimpleMessageListenerContainer(connections);
            container.setQueueNames(queue);
            container.setPrefetchCount(2);
            container.setMessageListener(
                    (MessageListener)
                            message -> {
                                entered.countDown();
                                try {
                                    if (!finish.await(4, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException(
                                                "Test delivery did not finish");
                                    }
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(
                                            "Test delivery interrupted", interrupted);
                                }
                                if (fail) {
                                    throw new IllegalStateException("Test transaction rolled back");
                                }
                            });
            BrokerDrainConfiguration.configure(container);
            try {
                container.start();
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                container.stop(drained::countDown);
                assertThat(drained.await(50, TimeUnit.MILLISECONDS)).isFalse();
                finish.countDown();
                assertThat(drained.await(5, TimeUnit.SECONDS)).isTrue();
                var remaining = new ArrayList<Object>();
                remaining.add(template.receiveAndConvert(queue, 3000));
                if (fail) {
                    remaining.add(template.receiveAndConvert(queue, 3000));
                    assertThat(remaining).containsExactlyInAnyOrder("active", "buffered");
                } else {
                    assertThat(remaining).containsExactly("buffered");
                }
                assertThat(template.receive(queue, 100)).isNull();
            } finally {
                finish.countDown();
                container.destroy();
                admin.deleteQueue(queue);
            }
        }
    }
}
