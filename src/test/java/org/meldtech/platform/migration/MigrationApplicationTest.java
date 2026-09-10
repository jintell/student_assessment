package org.meldtech.platform.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MigrationApplicationTest {

    @Test
    void recognizesOnlyTheDedicatedMigrationArgument() {
        assertThat(MigrationApplication.isRequested(new String[] {"--migrate-only"})).isTrue();
        assertThat(MigrationApplication.isRequested(new String[] {"--spring.profiles.active=api"}))
                .isFalse();
    }
}
