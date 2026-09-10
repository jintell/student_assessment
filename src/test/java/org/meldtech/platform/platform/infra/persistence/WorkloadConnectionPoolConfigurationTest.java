package org.meldtech.platform.platform.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.r2dbc.pool.ConnectionPool;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
                                                            ConnectionPool.class)))
                                    .isEqualTo(6);
                            assertThat(
                                            maxSize(
                                                    context.getBean(
                                                            "examPathConnectionFactory",
                                                            ConnectionPool.class)))
                                    .isEqualTo(8);
                        });
    }

    @Test
    void workerProfileCreatesTenConnectionPool() {
        contextRunner("worker", 10)
                .run(
                        context -> {
                            assertThat(context).hasBean("workerConnectionFactory");
                            assertThat(
                                            maxSize(
                                                    context.getBean(
                                                            "workerConnectionFactory",
                                                            ConnectionPool.class)))
                                    .isEqualTo(10);
                        });
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
                                                            ConnectionPool.class)))
                                    .isEqualTo(5);
                        });
    }

    private ApplicationContextRunner contextRunner(String poolName, int maxSize) {
        String username = "app_" + (poolName.equals("pindist") ? "pindist" : poolName);
        return new ApplicationContextRunner()
                .withUserConfiguration(WorkloadConnectionPoolConfiguration.class)
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

    private int maxSize(ConnectionPool pool) {
        return pool.getMetrics().orElseThrow().getMaxAllocatedSize();
    }
}
