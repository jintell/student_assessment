package org.meldtech.platform.platform.infra.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class RedisSecretConfigurationTest {

    @Test
    void productionRedisPasswordHasOnlyAnExternalConfigTreeSource() throws IOException {
        String configuration;
        try (var input = new ClassPathResource("application.yaml").getInputStream()) {
            configuration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(configuration)
                .contains(
                        "optional:configtree:${CBT_REDIS_SECRETS_PATH:/run/secrets/redis/}",
                        "on-profile: production",
                        "password: ${cbt.redis.password}")
                .doesNotContain(
                        "password: ${cbt.redis.password:",
                        "spring.data.redis.password=",
                        "redis://default:");
    }
}
