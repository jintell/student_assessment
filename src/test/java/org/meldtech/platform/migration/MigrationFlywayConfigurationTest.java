package org.meldtech.platform.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MigrationFlywayConfigurationTest {

    @Test
    void permitsExplicitNonTransactionalScriptsWithoutAllowingOutOfOrderMigrations() {
        MigrationSessionSettings settings =
                new MigrationSessionSettings(
                        Duration.ofMillis(250),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2),
                        Duration.ofMinutes(15),
                        Duration.ofSeconds(30));

        var configuration =
                MigrationApplication.flywayConfiguration(
                        "jdbc:postgresql://localhost/not-connected",
                        "app_migrator",
                        "unused",
                        "platform_migrations",
                        "test-refresh",
                        MigrationSchema.PLATFORM,
                        settings);

        assertThat(configuration.isMixed()).isTrue();
        assertThat(configuration.isOutOfOrder()).isFalse();
    }
}
