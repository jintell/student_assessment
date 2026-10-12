package org.meldtech.platform.platform.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.infra.observability.DatabaseQueryTelemetry;
import org.meldtech.platform.platform.infra.observability.TracingConnectionFactory;
import org.meldtech.platform.shared.kernel.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.r2dbc.core.DatabaseClient;

class WorkloadConnectionPoolConfigurationTest {

    private static final String DATABASE_URL = "r2dbc:postgresql://localhost/cbt_platform";
    private static final String EXTERNAL_TEST_PASSWORD = "external-test-secret";

    @Test
    void apiProfileCreatesSeparateGeneralAndExamPathPools() {
        contextRunner("api", 6)
                .withPropertyValues(
                        poolProperty("exam-path", "username", "app_api"),
                        poolProperty("exam-path", "password", EXTERNAL_TEST_PASSWORD),
                        poolProperty("exam-path", "url", DATABASE_URL),
                        poolProperty("exam-path", "initial-size", "0"),
                        poolProperty("exam-path", "max-size", "8"),
                        poolProperty("exam-path", "max-idle-time", "30m"))
                .run(
                        context -> {
                            assertThat(context).hasBean("apiConnectionFactory");
                            assertThat(context).hasBean("examPathConnectionFactory");
                            assertThat(
                                            maxSize(
                                                    context.getBean(
                                                            "apiConnectionFactory",
                                                            ConnectionFactory.class)))
                                    .isEqualTo(6);
                            assertThat(
                                            maxSize(
                                                    context.getBean(
                                                            "examPathConnectionFactory",
                                                            ConnectionFactory.class)))
                                    .isEqualTo(8);
                        });
    }

    @Test
    void workerDefaultDatabaseClientUsesBackgroundPoolInsteadOfTheReservedPools() {
        workerContextRunner()
                .withConfiguration(AutoConfigurations.of(R2dbcAutoConfiguration.class))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed().hasSingleBean(DatabaseClient.class);
                            assertThat(context.getBean(DatabaseClient.class).getConnectionFactory())
                                    .isSameAs(context.getBean("workerConnectionFactory"));
                            assertThat(context.getBean(ConnectionFactory.class))
                                    .isSameAs(context.getBean("workerConnectionFactory"));
                        });
    }

    @Test
    void workerProfilePartitionsTenConnectionsIntoThreeIndependentPools() {
        workerContextRunner()
                .run(
                        context -> {
                            assertThat(context).hasBean("workerConnectionFactory");
                            assertThat(
                                            maxSize(
                                                    context.getBean(
                                                            "workerConnectionFactory",
                                                            ConnectionFactory.class)))
                                    .isEqualTo(4);
                            ConnectionPool background =
                                    pool(
                                            context.getBean(
                                                    "workerConnectionFactory",
                                                    ConnectionFactory.class));
                            ConnectionPool grading =
                                    pool(
                                            context.getBean(
                                                    "gradingConnectionFactory",
                                                    ConnectionFactory.class));
                            ConnectionPool locks =
                                    pool(
                                            context.getBean(
                                                    "sweepLockConnectionFactory",
                                                    ConnectionFactory.class));
                            assertThat(background).isNotSameAs(grading).isNotSameAs(locks);
                            assertThat(grading).isNotSameAs(locks);
                            assertThat(grading.getMetrics().orElseThrow().getMaxAllocatedSize())
                                    .isEqualTo(4);
                            assertThat(locks.getMetrics().orElseThrow().getMaxAllocatedSize())
                                    .isEqualTo(2);
                        });
    }

    @Test
    void rejectsAnExtraLockConnectionOrAnUnpartitionedWorkerPool() {
        for (String override :
                new String[] {
                    poolProperty("sweep-lock", "max-size", "3"),
                    poolProperty("worker", "max-size", "10"),
                    poolProperty("grading", "max-size", "5"),
                    poolProperty("sweep-lock", "username", "app_api"),
                    poolProperty(
                            "sweep-lock", "url", "r2dbc:pool:postgresql://localhost/cbt_platform")
                }) {
            workerContextRunner()
                    .withPropertyValues(override)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    private ApplicationContextRunner workerContextRunner() {
        ApplicationContextRunner runner = contextRunner("worker", 4);
        for (String name : new String[] {"grading", "sweep-lock"}) {
            runner =
                    runner.withPropertyValues(
                            poolProperty(name, "username", "app_worker"),
                            poolProperty(name, "password", EXTERNAL_TEST_PASSWORD),
                            poolProperty(name, "url", DATABASE_URL),
                            poolProperty(name, "initial-size", "0"),
                            poolProperty(name, "max-size", name.equals("grading") ? "4" : "2"),
                            poolProperty(name, "max-idle-time", "30m"));
        }
        return runner;
    }

    @Test
    void pinDistributionProfileCreatesFiveConnectionPool() {
        contextRunner("pindist", 5)
                .run(
                        context -> {
                            assertThat(context).hasBean("pinDistributionConnectionFactory");
                            assertThat(
                                            maxSize(
                                                    context.getBean(
                                                            "pinDistributionConnectionFactory",
                                                            ConnectionFactory.class)))
                                    .isEqualTo(5);
                        });
    }

    private ApplicationContextRunner contextRunner(String poolName, int maxSize) {
        String username = "app_" + (poolName.equals("pindist") ? "pindist" : poolName);
        return new ApplicationContextRunner()
                .withUserConfiguration(WorkloadConnectionPoolConfiguration.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(OpenTelemetry.class, OpenTelemetry::noop)
                .withBean(DatabaseQueryTelemetry.class, () -> DatabaseQueryTelemetry.NOOP)
                .withBean(Clock.class, () -> java.time.Instant::now)
                .withPropertyValues(
                        "spring.profiles.active=" + poolName,
                        poolProperty(poolName, "username", username),
                        poolProperty(poolName, "password", EXTERNAL_TEST_PASSWORD),
                        poolProperty(poolName, "url", DATABASE_URL),
                        poolProperty(poolName, "initial-size", "0"),
                        poolProperty(poolName, "max-size", Integer.toString(maxSize)),
                        poolProperty(poolName, "max-idle-time", "30m"));
    }

    private String poolProperty(String poolName, String property, String value) {
        return "cbt.database.pools.%s.%s=%s".formatted(poolName, property, value);
    }

    private int maxSize(ConnectionFactory connectionFactory) {
        return pool(connectionFactory).getMetrics().orElseThrow().getMaxAllocatedSize();
    }

    private ConnectionPool pool(ConnectionFactory connectionFactory) {
        assertThat(connectionFactory).isInstanceOf(TracingConnectionFactory.class);
        ConnectionFactory secured = ((TracingConnectionFactory) connectionFactory).delegate();
        assertThat(secured).isInstanceOf(SecurityContextInitializer.class);
        return (ConnectionPool) ((SecurityContextInitializer) secured).delegate();
    }
}
