package org.meldtech.platform.platform.infra.outbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("worker")
class BrokerCredentialConfiguration {

    @Bean
    BrokerCredentialAvailability brokerCredentialAvailability(Environment environment) {
        requireCredential(environment, "spring.rabbitmq.username");
        requireCredential(environment, "spring.rabbitmq.password");
        return new BrokerCredentialAvailability();
    }

    private static void requireCredential(Environment environment, String propertyName) {
        String credential = environment.getProperty(propertyName);
        if (credential == null || credential.isBlank()) {
            throw new IllegalStateException("Required broker credential is unavailable");
        }
    }

    static final class BrokerCredentialAvailability {}
}
