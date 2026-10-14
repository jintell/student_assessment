package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.r2dbc.spi.ConnectionFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RuntimeBulkheadConfigurationTest {
    @Test
    void consumesExistingPoolsAndClosesOwnedSchedulers() {
        ConnectionFactory examPool = mock(ConnectionFactory.class);
        ConnectionFactory generalPool = mock(ConnectionFactory.class);
        new ApplicationContextRunner()
                .withUserConfiguration(RuntimeBulkheadConfiguration.class)
                .withPropertyValues("spring.profiles.active=api")
                .withBean("examPathConnectionFactory", ConnectionFactory.class, () -> examPool)
                .withBean("apiConnectionFactory", ConnectionFactory.class, () -> generalPool)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBeansOfType(ConnectionFactory.class)).hasSize(2);
                            var exam = context.getBean("examPathLane", RuntimeLane.class);
                            var admin = context.getBean("administrativeLane", RuntimeLane.class);
                            assertThat(exam.connections()).isSameAs(examPool);
                            assertThat(admin.connections()).isSameAs(generalPool);
                            assertThat(exam.scheduler()).isNotSameAs(admin.scheduler());
                            Mono<String> contextValue =
                                    Mono.deferContextual(values -> Mono.just(values.get("actor")));
                            StepVerifier.create(
                                            exam.execute(() -> contextValue)
                                                    .contextWrite(
                                                            values ->
                                                                    values.put(
                                                                            "actor", "test-actor")))
                                    .expectNext("test-actor")
                                    .verifyComplete();
                        });
    }

    @Test
    void administrativeSaturationDoesNotConsumeExamCapacity() throws Exception {
        var configuration = new RuntimeBulkheadConfiguration();
        var admin = configuration.administrativeScheduler();
        var exam = configuration.examPathScheduler();
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        int rejected = 0;
        try {
            for (int i = 0; i < 2; i++) {
                admin.schedule(
                        () -> {
                            entered.countDown();
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        });
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 100; i++) {
                try {
                    admin.schedule(() -> {});
                } catch (RejectedExecutionException exhausted) {
                    rejected++;
                }
            }
            assertThat(rejected).isPositive();
            StepVerifier.create(Mono.just("exam").subscribeOn(exam))
                    .expectNext("exam")
                    .expectComplete()
                    .verify(java.time.Duration.ofSeconds(1));
        } finally {
            release.countDown();
            admin.dispose();
            exam.dispose();
        }
        assertThat(admin.isDisposed()).isTrue();
        assertThat(exam.isDisposed()).isTrue();
    }
}
