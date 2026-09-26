package org.meldtech.platform.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class MigrationApplicationTest {

    @Test
    void recognizesOnlyTheDedicatedMigrationArgument() {
        assertThat(MigrationApplication.isRequested(new String[] {"--migrate-only"})).isTrue();
        assertThat(MigrationApplication.isRequested(new String[] {"--spring.profiles.active=api"}))
                .isFalse();
    }

    @Test
    void rejectsMigrationStartupUnlessItUsesTheDedicatedMigratorRole() {
        assertThatThrownBy(
                        () ->
                                MigrationApplication.run(
                                        new String[] {
                                            "--migrate-only",
                                            "--cbt.migration.jdbc-url="
                                                    + "jdbc:postgresql://localhost/unused",
                                            "--cbt.migration.username=app_api",
                                            "--cbt.database.roles.app-migrator.password=unused"
                                        }))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Migration entrypoint must connect as app_migrator");
    }

    @Test
    void servingProfilesCannotAssumeTheMigratorRole() throws IOException {
        String applicationConfiguration;
        try (var input = new ClassPathResource("application.yaml").getInputStream()) {
            applicationConfiguration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(applicationConfiguration)
                .contains("flyway:\n    enabled: false")
                .contains("on-profile: api", "on-profile: worker", "on-profile: pindist")
                .doesNotContain(
                        "username: app_migrator", "cbt.database.roles.app-migrator.password");
    }
}
