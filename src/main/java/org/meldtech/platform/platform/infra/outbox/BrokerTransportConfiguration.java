package org.meldtech.platform.platform.infra.outbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("worker")
class BrokerTransportConfiguration {

    @Bean
    BrokerTransportSecurity brokerTransportSecurity(Environment environment) {
        requireEnabled(environment, "spring.rabbitmq.ssl.enabled");
        requireEnabled(environment, "spring.rabbitmq.ssl.validate-server-certificate");
        requireEnabled(environment, "spring.rabbitmq.ssl.verify-hostname");
        return new BrokerTransportSecurity();
    }

    private static void requireEnabled(Environment environment, String propertyName) {
        if (!environment.getProperty(propertyName, Boolean.class, false)) {
            throw new IllegalStateException("Broker TLS verification must remain enabled");
        }
    }

    static final class BrokerTransportSecurity {}
}
