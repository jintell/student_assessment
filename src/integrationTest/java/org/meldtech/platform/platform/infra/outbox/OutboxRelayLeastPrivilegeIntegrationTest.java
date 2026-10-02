package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class OutboxRelayLeastPrivilegeIntegrationTest extends OutboxPostgreSqlIntegrationTestSupport {

    @Test
    void relayRoleHasOnlySelectAndUpdateOnTheOutbox() throws Exception {
        assertThat(
                        queryInt(
                                """
                                SELECT count(*)
                                FROM information_schema.role_table_grants
                                WHERE grantee = 'app_outbox_relay'
                                  AND table_schema IN (
                                      'tenancy', 'iam', 'academic', 'people',
                                      'questionbank', 'authoring', 'examaccess', 'delivery',
                                      'grading', 'result', 'correction', 'notification'
                                  )
                                """))
                .as("relay grants in module-owned schemas")
                .isZero();

        assertPrivilegeDenied(
                "outbox insert",
                """
                INSERT INTO outbox.outbox_event (
                    outbox_event_id, tenant_id, aggregate_type, aggregate_id,
                    event_type, payload, correlation_id, occurred_at
                ) VALUES (
                    '01950f47-6000-7004-8000-000000000001',
                    '01950f47-6000-7004-8000-000000000002',
                    'Reference', 'reference-1', 'platform.ReferenceEvent.v1',
                    '{}'::jsonb, '01ARZ3NDEKTSV4RRFFQ69G5FAV', CURRENT_TIMESTAMP
                )
                """);
        assertPrivilegeDenied("outbox delete", "DELETE FROM outbox.outbox_event WHERE false");
        assertPrivilegeDenied(
                "module select", "SELECT count(*) FROM delivery.outbox_atomicity_probe");
        assertPrivilegeDenied(
                "module update",
                "UPDATE delivery.outbox_atomicity_probe SET probe_id = probe_id WHERE false");
    }

    private void assertPrivilegeDenied(String operation, String sql) {
        assertThatThrownBy(() -> executeAsRelay(sql))
                .as(operation)
                .isInstanceOf(SQLException.class)
                .satisfies(
                        failure ->
                                assertThat(((SQLException) failure).getSQLState())
                                        .isEqualTo("42501"));
    }

    private void executeAsRelay(String sql) throws SQLException {
        try (Connection connection = clusterOwnerConnection();
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("SET LOCAL ROLE app_outbox_relay");
                statement.execute("SET LOCAL app.platform_scope = 'outbox_relay'");
                statement.execute(sql);
            } finally {
                connection.rollback();
            }
        }
    }
}
