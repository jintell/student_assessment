package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.DirectMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

class BrokerDrainConfigurationTest {

    @Test
    void bothContainerKindsRetainInFlightAcknowledgementsAndRequeueBufferedDeliveries() {
        var configuration = new BrokerDrainConfiguration();
        var simple = new SimpleMessageListenerContainer();
        var direct = new DirectMessageListenerContainer();
        configuration.simpleBrokerDrain().configure(simple);
        configuration.directBrokerDrain().configure(direct);
        for (var container : java.util.List.of(simple, direct)) {
            assertThat(container.getPhase()).isEqualTo(BrokerDrainConfiguration.PHASE);
            assertThat(ReflectionTestUtils.getField(container, "shutdownTimeout")).isEqualTo(5000L);
            assertThat(ReflectionTestUtils.getField(container, "forceStop")).isEqualTo(true);
            assertThat(ReflectionTestUtils.getField(container, "forceCloseChannel"))
                    .isEqualTo(true);
            assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.AUTO);
        }
    }

    @Test
    void registryInheritsTheDrainPhaseFromFactoryCreatedContainers() {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(mock(ConnectionFactory.class));
        factory.setContainerCustomizer(new BrokerDrainConfiguration().simpleBrokerDrain());
        var endpoint = new SimpleRabbitListenerEndpoint();
        endpoint.setId("test-drain");
        endpoint.setQueueNames("test-drain");
        endpoint.setMessageListener(message -> {});
        var registry = new RabbitListenerEndpointRegistry();
        try {
            registry.registerListenerContainer(endpoint, factory);
            assertThat(registry.getPhase()).isEqualTo(BrokerDrainConfiguration.PHASE);
        } finally {
            registry.destroy();
        }
    }

    @Test
    void unconfiguredCustomFactoriesFailBeforeStartingConsumers() {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(mock(ConnectionFactory.class));
        var endpoint = new SimpleRabbitListenerEndpoint();
        endpoint.setId("unsafe-drain");
        endpoint.setQueueNames("unsafe-drain");
        endpoint.setMessageListener(message -> {});
        var registry = new RabbitListenerEndpointRegistry();
        try {
            registry.registerListenerContainer(endpoint, factory);
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("registry", registry);
            var validator = new BrokerDrainConfiguration().brokerDrainValidator(beans);
            assertThatIllegalStateException().isThrownBy(validator::afterSingletonsInstantiated);
        } finally {
            registry.destroy();
        }
    }

    @Test
    void acknowledgementBeforeProcessingIsRejected() {
        var container = new SimpleMessageListenerContainer();
        container.setAcknowledgeMode(AcknowledgeMode.NONE);
        assertThatIllegalStateException()
                .isThrownBy(() -> BrokerDrainConfiguration.configure(container));
    }

    @Test
    void policyIsWorkerOnly() {
        for (String role : java.util.List.of("api", "worker", "pindist")) {
            new ApplicationContextRunner()
                    .withUserConfiguration(BrokerDrainConfiguration.class)
                    .withPropertyValues("spring.profiles.active=" + role)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(context.containsBean("simpleBrokerDrain"))
                                        .isEqualTo(role.equals("worker"));
                            });
        }
    }
}
