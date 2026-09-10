package org.meldtech.platform.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

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
}
