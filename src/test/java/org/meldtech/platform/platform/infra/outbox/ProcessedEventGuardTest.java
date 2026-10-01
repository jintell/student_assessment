package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProcessedEventGuardTest {

    @Test
    void reservationUsesEventIdentityAndConflictNoOp() {
        String sql = ProcessedEventGuard.reserveSql(ConsumerModule.RESULT.schema());

        assertThat(sql).contains("INSERT INTO result.processed_event");
        assertThat(sql).contains("ON CONFLICT (outbox_event_id) DO NOTHING");
        assertThat(sql).contains("RETURNING outbox_event_id");
    }

    @Test
    void migrationTemplateForcesTenantIsolation() throws Exception {
        String template =
                Files.readString(Path.of("src/main/resources/db/templates/processed_event.sql"));

        assertThat(template).contains("outbox_event_id uuid PRIMARY KEY");
        assertThat(template).contains("FORCE ROW LEVEL SECURITY");
        assertThat(template).contains("current_setting('app.tenant_id', false)");
    }
}
