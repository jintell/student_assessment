package org.meldtech.platform.platform.infra.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkloadDatabasePropertiesTest {

    private static final String URL = "r2dbc:postgresql://localhost/cbt_platform";
    private static final Duration MAX_IDLE_TIME = Duration.ofMinutes(30);

    @Test
    void rejectsAMissingRequiredPool() {
        WorkloadDatabaseProperties properties = new WorkloadDatabaseProperties(Map.of());

        assertThatThrownBy(() -> properties.requiredPool("api"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Required database pool is missing: api");
    }

    @Test
    void rejectsIncompleteAndInvalidPoolSettings() {
        assertInvalidPool(
                " ",
                "app_api",
                "secret",
                0,
                1,
                MAX_IDLE_TIME,
                "Database pool URL must be configured");
        assertInvalidPool(
                URL,
                " ",
                "secret",
                0,
                1,
                MAX_IDLE_TIME,
                "Database pool username must be configured");
        assertInvalidPool(
                URL,
                "app_api",
                "",
                0,
                1,
                MAX_IDLE_TIME,
                "Database pool password must be configured");
        assertInvalidPool(
                URL, "app_api", "secret", 2, 1, MAX_IDLE_TIME, "Database pool size is invalid");
        assertInvalidPool(
                URL,
                "app_api",
                "secret",
                0,
                1,
                Duration.ZERO,
                "Database pool max idle time must be positive");
    }

    private static void assertInvalidPool(
            String url,
            String username,
            String password,
            int initialSize,
            int maxSize,
            Duration maxIdleTime,
            String expectedMessage) {
        assertThatThrownBy(
                        () ->
                                new WorkloadDatabaseProperties.PoolProperties(
                                        url, username, password, initialSize, maxSize, maxIdleTime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expectedMessage);
    }
}
