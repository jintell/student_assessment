package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meldtech.platform.audit.testing.AuditPostgreSqlFixture;

class OutboxRetentionIntegrationTest extends OutboxPostgreSqlIntegrationTestSupport {

    @Test
    void expiredPublishedPartitionDetachesWhileIndependentAuditEvidenceSurvives() throws Exception {
        try (Connection connection = clusterOwnerConnection()) {
            connection.setAutoCommit(false);
            try {
                String auditBefore = auditSnapshot(connection);
                if (auditBefore.equals("[]")) {
                    AuditPostgreSqlFixture.seed(connection);
                    auditBefore = auditSnapshot(connection);
                }
                seedPartition(connection, "PUBLISHED", false);

                assertThat(detach(connection)).isTrue();
                assertThat(attached(connection)).isFalse();
                try (var statement = connection.createStatement()) {
                    statement.execute("DROP TABLE outbox.outbox_event_20260119");
                }

                assertThat(auditSnapshot(connection)).isEqualTo(auditBefore).isNotEqualTo("[]");
            } finally {
                connection.rollback();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "PENDING", "RECENTLY_PUBLISHED"})
    void unresolvedOrRecentlyPublishedRowPreventsDetachingOldPartition(String state)
            throws Exception {
        try (Connection connection = clusterOwnerConnection()) {
            connection.setAutoCommit(false);
            try {
                seedPartition(connection, "PUBLISHED", false);
                seedPartition(
                        connection, state.equals("RECENTLY_PUBLISHED") ? "PUBLISHED" : state, true);

                assertThat(detach(connection)).isFalse();
                assertThat(attached(connection)).isTrue();
                try (var statement = connection.createStatement();
                        var rows =
                                statement.executeQuery(
                                        "SELECT count(*) FROM outbox.outbox_event_20260119")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(2);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static void seedPartition(Connection connection, String state, boolean recent)
            throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SELECT outbox.attach_week_partition('2026-01-19'::date)");
        }
        try (var statement =
                connection.prepareStatement(
                        """
                INSERT INTO outbox.outbox_event (
                    outbox_event_id, tenant_id, aggregate_type, aggregate_id, event_type,
                    payload, correlation_id, occurred_at, created_at, state,
                    published_at, attempt_count, last_error
                ) VALUES (
                    gen_random_uuid(), ?, 'Reference', 'fixture-entity-0-0',
                    'platform.ReferenceEvent.v1', '{}', '01ARZ3NDEKTSV4RRFFQ69G5FAV',
                    '2026-01-20T00:00:00Z', '2026-01-20T00:00:00Z', ?,
                    CASE WHEN ? = 'PUBLISHED' THEN
                        CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE '2026-01-21T00:00:00Z'::timestamptz END
                    END,
                    CASE WHEN ? = 'FAILED' THEN 8 ELSE 0 END,
                    CASE WHEN ? = 'FAILED' THEN 'BROKER_UNAVAILABLE' END
                )
                """)) {
            statement.setObject(1, AuditPostgreSqlFixture.TENANT_ID);
            statement.setString(2, state);
            statement.setString(3, state);
            statement.setBoolean(4, recent);
            statement.setString(5, state);
            statement.setString(6, state);
            statement.executeUpdate();
        }
    }

    private static boolean detach(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL ROLE app_outbox_maintenance");
            try (var rows =
                    statement.executeQuery(
                            "SELECT outbox.detach_expired_partition('outbox_event_20260119'::name)")) {
                rows.next();
                return rows.getBoolean(1);
            } finally {
                statement.execute("RESET ROLE");
            }
        }
    }

    private static boolean attached(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows =
                        statement.executeQuery(
                                """
                        SELECT EXISTS (SELECT 1 FROM pg_inherits
                            WHERE inhrelid = 'outbox.outbox_event_20260119'::regclass
                              AND inhparent = 'outbox.outbox_event'::regclass)
                        """)) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    private static String auditSnapshot(Connection connection) throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        """
                SELECT coalesce(jsonb_agg(to_jsonb(event) ORDER BY audit_event_id), '[]')::text
                FROM audit.audit_event event WHERE tenant_id = ?
                """)) {
            statement.setObject(1, AuditPostgreSqlFixture.TENANT_ID);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }
}
