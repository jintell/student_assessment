package org.meldtech.platform.platform.infra.outbox;

import org.meldtech.platform.platform.api.RoleScoped;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.DirectMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Uses the listener containers' lifecycle, including their pending-reply acknowledgement drain. */
@Configuration(proxyBeanMethods = false)
@RoleScoped("worker")
class BrokerDrainConfiguration {

    static final int PHASE = WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE - 1;
    static final long TIMEOUT_MILLIS = 5000;

    @Bean
    ContainerCustomizer<SimpleMessageListenerContainer> simpleBrokerDrain() {
        return BrokerDrainConfiguration::configure;
    }

    @Bean
    ContainerCustomizer<DirectMessageListenerContainer> directBrokerDrain() {
        return BrokerDrainConfiguration::configure;
    }

    @Bean
    static BeanPostProcessor brokerContainerDrainPolicy() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof AbstractMessageListenerContainer container) {
                    configure(container);
                } else if (bean instanceof MessageListenerContainer) {
                    throw new IllegalStateException("Unsupported broker drain container");
                }
                return bean;
            }
        };
    }

    @Bean
    SmartInitializingSingleton brokerDrainValidator(ListableBeanFactory beans) {
        return () ->
                beans.getBeansOfType(RabbitListenerEndpointRegistry.class)
                        .values()
                        .forEach(
                                registry ->
                                        registry.getListenerContainers()
                                                .forEach(
                                                        container -> {
                                                            if (!(container
                                                                            instanceof
                                                                            AbstractMessageListenerContainer
                                                                                    managed)
                                                                    || registry.getPhase() != PHASE
                                                                    || managed.getPhase()
                                                                            != PHASE) {
                                                                throw new IllegalStateException(
                                                                        "Broker drain phase is not configured");
                                                            }
                                                            configure(managed);
                                                        }));
    }

    static void configure(AbstractMessageListenerContainer container) {
        if (container.getAcknowledgeMode() == AcknowledgeMode.NONE) {
            throw new IllegalStateException(
                    "Broker drain requires acknowledgement after processing");
        }
        container.setPhase(PHASE);
        container.setShutdownTimeout(TIMEOUT_MILLIS);
        container.setForceStop(true);
        container.setForceCloseChannel(true);
    }
}
