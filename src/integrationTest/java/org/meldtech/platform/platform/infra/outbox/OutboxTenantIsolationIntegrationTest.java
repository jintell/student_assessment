package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxTenantIsolationIntegrationTest extends OutboxPostgreSqlIntegrationTestSupport {

    private static final UUID TENANT_A = UUID.fromString("01950f47-6000-7003-8000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("01950f47-6000-7003-8000-000000000002");

    @Test
    void moduleContextIsTenantScopedAndRelayContextIsCrossTenant() throws Exception {
        seedEvent(TENANT_A, UUID.fromString("01950f47-6000-7003-9000-000000000001"));
        seedEvent(TENANT_B, UUID.fromString("01950f47-6000-7003-9000-000000000002"));
        executeAsClusterOwner("GRANT SELECT ON outbox.outbox_event TO app_delivery");
        try {
            assertThat(
                            queryAsModuleWithTenant(
                                    TENANT_A, "SELECT count(*) FROM outbox.outbox_event"))
                    .as("the module sees only its installed tenant")
                    .isEqualTo(1);
            assertThat(
                            queryAsModuleWithTenant(
                                    TENANT_A,
                                    "SELECT count(*) FROM outbox.outbox_event "
                                            + "WHERE tenant_id = '"
                                            + TENANT_B
                                            + "'"))
                    .as("the module cannot select another tenant explicitly")
                    .isZero();
            assertThatThrownBy(this::queryAsModuleWithoutTenant)
                    .isInstanceOf(SQLException.class)
                    .satisfies(
                            failure ->
                                    assertThat(((SQLException) failure).getSQLState())
                                            .isIn("42704", "22P02"));
            assertThat(queryAsRelay("SELECT count(*) FROM outbox.outbox_event"))
                    .as("the closed relay sentinel admits the cross-tenant drain")
                    .isEqualTo(2);
        } finally {
            executeAsClusterOwner("REVOKE SELECT ON outbox.outbox_event FROM app_delivery");
        }
    }

    private void seedEvent(UUID tenantId, UUID eventId) throws SQLException {
        try (Connection connection = clusterOwnerConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                INSERT INTO outbox.outbox_event (
                                    outbox_event_id, tenant_id, aggregate_type, aggregate_id,
                                    event_type, payload, correlation_id, occurred_at, created_at
                                ) VALUES (?, ?, 'Reference', ?,
                                    'platform.ReferenceEvent.v1', '{}'::jsonb,
                                    '01ARZ3NDEKTSV4RRFFQ69G5FAV', ?, ?)
                                """)) {
            Instant now = Instant.now();
            statement.setObject(1, eventId);
            statement.setObject(2, tenantId);
            statement.setString(3, eventId.toString());
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setTimestamp(5, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private int queryAsModuleWithTenant(UUID tenantId, String sql) throws SQLException {
        try (Connection connection = clusterOwnerConnection();
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET LOCAL ROLE app_delivery");
            statement.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            try (var result = statement.executeQuery(sql)) {
                result.next();
                return result.getInt(1);
            } finally {
                connection.rollback();
            }
        }
    }

    private int queryAsModuleWithoutTenant() throws SQLException {
        try (Connection connection = clusterOwnerConnection();
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET LOCAL ROLE app_delivery");
            try {
                statement.executeQuery("SELECT count(*) FROM outbox.outbox_event");
                throw new AssertionError("Missing tenant context unexpectedly permitted a query");
            } finally {
                connection.rollback();
            }
        }
    }

    private int queryAsRelay(String sql) throws SQLException {
        try (Connection connection = clusterOwnerConnection();
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET LOCAL ROLE app_outbox_relay");
            statement.execute("SET LOCAL app.platform_scope = 'outbox_relay'");
            try (var result = statement.executeQuery(sql)) {
                result.next();
                return result.getInt(1);
            } finally {
                connection.rollback();
            }
        }
    }
}
