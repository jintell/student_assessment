package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BrokerCredentialConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(BrokerCredentialConfiguration.class)
                    .withPropertyValues("spring.profiles.active=worker");

    @Test
    void workerStartupFailsWhenBrokerCredentialIsUnavailable() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Required broker credential is unavailable");
                });
    }

    @Test
    void workerStartsWhenExternalBrokerCredentialsAreAvailable() {
        contextRunner
                .withPropertyValues(
                        "spring.rabbitmq.username=outbox-relay",
                        "spring.rabbitmq.password=external-test-value")
                .run(
                        context ->
                                assertThat(context)
                                        .hasSingleBean(
                                                BrokerCredentialConfiguration
                                                        .BrokerCredentialAvailability.class));
    }

    @Test
    void workerConfigurationUsesMandatoryExternalConfigTree() throws IOException {
        String applicationYaml = Files.readString(Path.of("src/main/resources/application.yaml"));

        assertThat(applicationYaml)
                .contains("configtree:${CBT_BROKER_SECRETS_PATH:/run/secrets/broker/}")
                .doesNotContain(
                        "optional:configtree:${CBT_BROKER_SECRETS_PATH:/run/secrets/broker/}");
    }
}
