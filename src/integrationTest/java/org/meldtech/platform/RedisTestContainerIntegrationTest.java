package org.meldtech.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;

class RedisTestContainerIntegrationTest {

    @Test
    void stageEightProvidesRedis() throws IOException, InterruptedException {
        GenericContainer<?> redis = RedisTestContainer.instance();
        redis.start();

        Container.ExecResult result = redis.execInContainer("redis-cli", "ping");

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout().trim()).isEqualTo("PONG");
    }
}
