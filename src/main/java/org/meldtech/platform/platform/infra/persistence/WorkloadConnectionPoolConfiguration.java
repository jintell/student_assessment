package org.meldtech.platform.platform.infra.persistence;

import static io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD;
import static io.r2dbc.spi.ConnectionFactoryOptions.USER;

import io.micrometer.core.instrument.MeterRegistry;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.meldtech.platform.platform.api.TransactionalCollaboration;
import org.springframework.beans.factory.annotation.Qualifier;
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
    ConnectionFactory apiConnectionFactory(
            WorkloadDatabaseProperties properties, MeterRegistry meterRegistry) {
        return securedPool("api", properties.requiredPool("api"), meterRegistry);
    }

    @Bean("examPathConnectionFactory")
    @Profile("api")
    ConnectionFactory examPathConnectionFactory(
            WorkloadDatabaseProperties properties, MeterRegistry meterRegistry) {
        return securedPool("exam-path", properties.requiredPool("exam-path"), meterRegistry);
    }

    @Bean("workerConnectionFactory")
    @Profile("worker")
    ConnectionFactory workerConnectionFactory(
            WorkloadDatabaseProperties properties, MeterRegistry meterRegistry) {
        return securedPool("worker", properties.requiredPool("worker"), meterRegistry);
    }

    @Bean("pinDistributionConnectionFactory")
    @Profile("pindist")
    ConnectionFactory pinDistributionConnectionFactory(
            WorkloadDatabaseProperties properties, MeterRegistry meterRegistry) {
        return securedPool("pindist", properties.requiredPool("pindist"), meterRegistry);
    }

    @Bean
    @Profile("api")
    TransactionalCollaboration transactionalCollaboration(
            @Qualifier("examPathConnectionFactory") ConnectionFactory connectionFactory) {
        if (!(connectionFactory instanceof SecurityContextInitializer initializer)) {
            throw new IllegalStateException(
                    "Exam-path connection factory must enforce database security context");
        }
        return new DefaultTransactionalCollaboration(initializer);
    }

    private static ConnectionFactory securedPool(
            String poolName,
            WorkloadDatabaseProperties.PoolProperties properties,
            MeterRegistry meterRegistry) {
        ConnectionFactoryOptions options =
                ConnectionFactoryOptions.parse(properties.url())
                        .mutate()
                        .option(USER, properties.username())
                        .option(PASSWORD, properties.password())
                        .build();
        ConnectionFactory connectionFactory = ConnectionFactories.get(options);
        SecurityContextInitializer.DatabaseContextMetrics metrics =
                new SecurityContextInitializer.DatabaseContextMetrics(meterRegistry);
        ConnectionPoolConfiguration poolConfiguration =
                ConnectionPoolConfiguration.builder(connectionFactory)
                        .name(poolName)
                        .initialSize(properties.initialSize())
                        .maxSize(properties.maxSize())
                        .maxIdleTime(properties.maxIdleTime())
                        .preRelease(SecurityContextInitializer::resetBeforeRelease)
                        .build();
        return new SecurityContextInitializer(new ConnectionPool(poolConfiguration), metrics);
    }
}
