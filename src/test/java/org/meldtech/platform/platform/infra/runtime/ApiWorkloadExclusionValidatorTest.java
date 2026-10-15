package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ApiWorkloadExclusionValidatorTest {
    @Test
    void requestRoleContextHasNoSchedulerOrBrokerConsumer() {
        runner("api")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBeansOfType(RuntimeIntervalSource.class))
                                    .isEmpty();
                            assertThat(context.getBeansOfType(MessageListenerContainer.class))
                                    .isEmpty();
                            assertThat(context.getBeanFactory().getBeanDefinitionNames())
                                    .noneMatch(
                                            name -> {
                                                Class<?> type =
                                                        context.getBeanFactory()
                                                                .getType(name, false);
                                                return type != null
                                                        && RuntimeManifestVerifier.isBrokerConsumer(
                                                                type);
                                            });
                        });
    }

    @Test
    void intervalSourceFailsBeforeConstruction() {
        AtomicBoolean constructed = new AtomicBoolean();
        runner("api")
                .withBean(
                        RuntimeIntervalSource.class,
                        () -> {
                            constructed.set(true);
                            return new TestIntervalSource();
                        })
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasMessageContaining("api role contains interval source")
                                    .hasMessageContaining(RuntimeIntervalSource.class.getName());
                            assertThat(constructed).isFalse();
                        });
    }

    @Test
    void annotatedConsumerFailsBeforeConstruction() {
        AtomicBoolean constructed = new AtomicBoolean();
        runner("api")
                .withBean(
                        Consumer.class,
                        () -> {
                            constructed.set(true);
                            return new Consumer();
                        })
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasMessageContaining("api role contains broker consumer")
                                    .hasMessageContaining(Consumer.class.getName());
                            assertThat(constructed).isFalse();
                        });
    }

    @Test
    void listenerContainerFailsBeforeConstruction() {
        AtomicBoolean constructed = new AtomicBoolean();
        runner("api")
                .withBean(
                        MessageListenerContainer.class,
                        () -> {
                            constructed.set(true);
                            return mock(MessageListenerContainer.class);
                        })
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasMessageContaining("api role contains broker consumer");
                            assertThat(constructed).isFalse();
                        });
    }

    @Test
    void validatorIsAbsentOutsideApiRole() {
        runner("worker")
                .withBean(RuntimeIntervalSource.class, TestIntervalSource::new)
                .withBean(Consumer.class, Consumer::new)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context)
                                    .doesNotHaveBean(ApiWorkloadExclusionValidator.class);
                        });
    }

    private static ApplicationContextRunner runner(String role) {
        return new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=" + role)
                .withUserConfiguration(ApiWorkloadExclusionValidator.class);
    }

    private static final class TestIntervalSource implements RuntimeIntervalSource {}

    private static final class Consumer {
        @SuppressWarnings("UnusedMethod") // Inspected reflectively by the startup validator.
        @RabbitListener(queues = "reference", autoStartup = "false")
        void consume(String ignored) {}
    }
}
