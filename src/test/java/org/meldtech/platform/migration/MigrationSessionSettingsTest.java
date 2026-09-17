package org.meldtech.platform.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class MigrationSessionSettingsTest {

    @Test
    void createsBoundedFlywaySessionSqlFromApprovedValues() {
        MigrationSessionSettings settings = MigrationSessionSettings.from(approvedEnvironment());

        assertThat(settings.examCriticalLockTimeout()).isEqualTo(Duration.ofMillis(250));
        assertThat(settings.concurrentIndexLockTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(settings.flywayInitializationSql())
                .isEqualTo(
                        "SET lock_timeout = '2000ms'; SET statement_timeout = '900000ms'; "
                                + "SET idle_in_transaction_session_timeout = '30000ms'");
    }

    @Test
    void rejectsMissingOrInvertedSessionBounds() {
        assertThatThrownBy(() -> MigrationSessionSettings.from(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exam-critical-lock-timeout");

        MockEnvironment inverted = approvedEnvironment();
        inverted.setProperty("cbt.migration.session.exam-critical-lock-timeout", "3s");
        assertThatThrownBy(() -> MigrationSessionSettings.from(inverted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed");
    }

    private static MockEnvironment approvedEnvironment() {
        return new MockEnvironment()
                .withProperty("cbt.migration.session.exam-critical-lock-timeout", "250ms")
                .withProperty("cbt.migration.session.non-critical-lock-timeout", "2s")
                .withProperty("cbt.migration.session.concurrent-index-lock-timeout", "2s")
                .withProperty("cbt.migration.session.statement-timeout", "15m")
                .withProperty("cbt.migration.session.idle-in-transaction-timeout", "30s");
    }
}
