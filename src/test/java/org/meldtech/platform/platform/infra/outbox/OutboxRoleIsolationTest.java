package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OutboxRoleIsolationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            IntegrationTopologyConfiguration.class,
                            BrokerCredentialConfiguration.class,
                            BrokerTransportConfiguration.class);

    @Test
    void requestAndIsolatedProfilesExposeNoRelayOrBrokerAuthority() {
        for (String profile : new String[] {"api", "pindist"}) {
            contextRunner
                    .withPropertyValues("spring.profiles.active=" + profile)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(context.getBeansOfType(Declarables.class)).isEmpty();
                                assertThat(
                                                context.getBeansOfType(
                                                        BrokerCredentialConfiguration
                                                                .BrokerCredentialAvailability
                                                                .class))
                                        .isEmpty();
                                assertThat(
                                                context.getBeansOfType(
                                                        BrokerTransportConfiguration
                                                                .BrokerTransportSecurity.class))
                                        .isEmpty();
                                assertThat(context.getBeansOfType(OutboxBrokerPublisher.class))
                                        .isEmpty();
                                assertThat(context.getBeansOfType(OutboxPublicationStore.class))
                                        .isEmpty();
                                assertThat(context.getBeansOfType(OutboxClaimRepository.class))
                                        .isEmpty();
                                assertThat(context.getBeansOfType(RelayPublishStep.class))
                                        .isEmpty();
                                assertThat(context.getBeansOfType(RelaySingleton.class)).isEmpty();
                            });
        }
    }
}
