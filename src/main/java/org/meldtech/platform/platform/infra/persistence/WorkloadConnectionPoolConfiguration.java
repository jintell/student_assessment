package org.meldtech.platform.platform.infra.persistence;

import static io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD;
import static io.r2dbc.spi.ConnectionFactoryOptions.USER;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile({"api", "worker", "pindist"})
@EnableConfigurationProperties(WorkloadDatabaseProperties.class)
class WorkloadConnectionPoolConfiguration {

    @Bean("apiConnectionFactory")
    @Profile("api")
    ConnectionPool apiConnectionFactory(WorkloadDatabaseProperties properties) {
        return createPool("api", properties.requiredPool("api"));
    }

    @Bean("examPathConnectionFactory")
    @Profile("api")
    ConnectionPool examPathConnectionFactory(WorkloadDatabaseProperties properties) {
        return createPool("exam-path", properties.requiredPool("exam-path"));
    }

    @Bean("workerConnectionFactory")
    @Profile("worker")
    ConnectionPool workerConnectionFactory(WorkloadDatabaseProperties properties) {
        return createPool("worker", properties.requiredPool("worker"));
    }

    @Bean("pinDistributionConnectionFactory")
    @Profile("pindist")
    ConnectionPool pinDistributionConnectionFactory(WorkloadDatabaseProperties properties) {
        return createPool("pindist", properties.requiredPool("pindist"));
    }

    private static ConnectionPool createPool(
            String poolName, WorkloadDatabaseProperties.PoolProperties properties) {
        ConnectionFactoryOptions options =
                ConnectionFactoryOptions.parse(properties.url())
                        .mutate()
                        .option(USER, properties.username())
                        .option(PASSWORD, properties.password())
                        .build();
        ConnectionFactory connectionFactory = ConnectionFactories.get(options);
        ConnectionPoolConfiguration poolConfiguration =
                ConnectionPoolConfiguration.builder(connectionFactory)
                        .name(poolName)
                        .initialSize(properties.initialSize())
                        .maxSize(properties.maxSize())
                        .maxIdleTime(properties.maxIdleTime())
                        .build();
        return new ConnectionPool(poolConfiguration);
    }
}
