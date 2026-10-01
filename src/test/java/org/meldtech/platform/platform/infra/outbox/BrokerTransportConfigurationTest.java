package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BrokerTransportConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(BrokerTransportConfiguration.class)
                    .withPropertyValues(
                            "spring.profiles.active=worker",
                            "spring.rabbitmq.ssl.enabled=true",
                            "spring.rabbitmq.ssl.validate-server-certificate=true",
                            "spring.rabbitmq.ssl.verify-hostname=true");

    @Test
    void acceptsVerifiedTlsConfiguration() {
        contextRunner.run(
                context ->
                        assertThat(context)
                                .hasSingleBean(
                                        BrokerTransportConfiguration.BrokerTransportSecurity
                                                .class));
    }

    @Test
    void rejectsDisabledTls() {
        assertRejected("spring.rabbitmq.ssl.enabled=false");
    }

    @Test
    void rejectsDisabledCertificateValidation() {
        assertRejected("spring.rabbitmq.ssl.validate-server-certificate=false");
    }

    @Test
    void rejectsDisabledHostnameVerification() {
        assertRejected("spring.rabbitmq.ssl.verify-hostname=false");
    }

    private void assertRejected(String override) {
        contextRunner
                .withPropertyValues(override)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseMessage(
                                            "Broker TLS verification must remain enabled");
                        });
    }
}
