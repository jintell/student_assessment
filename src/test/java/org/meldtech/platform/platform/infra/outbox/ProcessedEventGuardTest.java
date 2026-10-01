package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.NoTransactionException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

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

    @Test
    void applyingAnEventRequiresAReactiveTransaction() {
        ProcessedEventGuard guard =
                new ProcessedEventGuard(
                        ConsumerModule.RESULT,
                        DatabaseClient.create(new NoOpConnectionFactory()),
                        mock(OutboxTelemetry.class));
        ConsumedEvent event =
                new ConsumedEvent(
                        "01950f47-6000-7000-8000-000000000001",
                        "01950f47-6000-7000-8000-000000000002",
                        EventType.parse("platform.ReferenceEvent.v1"));

        StepVerifier.create(guard.applyOnce(event, () -> Mono.just(ProcessedEventOutcome.APPLIED)))
                .expectError(NoTransactionException.class)
                .verify();
    }
}
