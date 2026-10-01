package org.meldtech.platform;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return PostgreSqlTestContainer.instance();
    }

    @Bean(destroyMethod = "")
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return RedisTestContainer.instance();
    }

    @Bean(destroyMethod = "")
    @ServiceConnection
    RabbitMQContainer rabbitMqContainer() {
        return RabbitMqTestContainer.instance();
    }
}
