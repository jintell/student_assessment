package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meldtech.platform.platform.api.SweepLock;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SweepLockConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withInitializer(
                            context -> context.getEnvironment().setActiveProfiles("worker"))
                    .withUserConfiguration(
                            SweepLockConfiguration.class,
                            SessionSweepLock.class,
                            TransactionSweepLock.class)
                    .withBean(
                            "sweepLockConnectionFactory",
                            ConnectionFactory.class,
                            () -> mock(ConnectionFactory.class));

    @Test
    void defaultsToExactlyOneSessionAdapter() {
        runner.run(
                context -> {
                    assertThat(context).hasSingleBean(SweepLock.class);
                    assertThat(context.getBean(SweepLock.class))
                            .isInstanceOf(SessionSweepLock.class);
                });
    }

    @Test
    void selectsExactlyOneTransactionAdapter() {
        runner.withPropertyValues(
                        "cbt.runtime.sweep-lock.mode=transaction",
                        "cbt.runtime.sweep-lock.pooling-mode=transaction")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(SweepLock.class);
                            assertThat(context.getBean(SweepLock.class))
                                    .isInstanceOf(TransactionSweepLock.class);
                        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "cbt.runtime.sweep-lock.mode=invalid",
                "cbt.runtime.sweep-lock.pooling-mode=invalid",
                "cbt.runtime.sweep-lock.pooling-mode=transaction"
            })
    void invalidOrIncompatibleSettingsFailStartup(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }
}
