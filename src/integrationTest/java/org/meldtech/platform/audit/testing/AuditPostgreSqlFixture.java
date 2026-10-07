package org.meldtech.platform.audit.testing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public final class AuditPostgreSqlFixture {

    public static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000071");
    public static final int SHARD_COUNT = 2;
    public static final List<String> RETENTION_CLASSES =
            List.of(
                    "RESULT_CORRECTION_EVIDENCE",
                    "RESULT_PUBLICATION_EVIDENCE",
                    "PIN_SECURITY_EVENT",
                    "GENERAL_AUDIT_EVENT");
    public static final List<LocalDate> PERIODS =
            List.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1));

    private static final String CORRELATION_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private static final LocalDate HELD_PERIOD = LocalDate.of(2026, 2, 1);
    private static final String HELD_RETENTION_CLASS = "RESULT_PUBLICATION_EVIDENCE";

    private AuditPostgreSqlFixture() {}

    public static Manifest seed(Connection connection) throws SQLException {
        return seed(connection, AuditPostgreSqlFixture::fixtureRecordHash);
    }

    public static Manifest seed(Connection connection, RecordHashFunction recordHashFunction)
            throws SQLException {
        boolean managedTransaction = connection.getAutoCommit();
        Savepoint savepoint = null;
        if (managedTransaction) {
            connection.setAutoCommit(false);
        } else {
            savepoint = connection.setSavepoint("audit_fixture");
        }

        try {
            requireUnseededTenant(connection);
            provisionTopology(connection);

            List<EventSeed> eventSeeds = eventSeeds();
            var seededEvents = new ArrayList<SeededEvent>(eventSeeds.size());
            for (EventSeed event : eventSeeds) {
                byte[] previousHash = currentHead(connection, event);
                byte[] recordHash = recordHashFunction.hash(previousHash.clone(), event);
                if (recordHash == null || recordHash.length != 32) {
                    throw new IllegalArgumentException(
                            "Audit fixture record hash must contain exactly 32 bytes");
                }
                insertEvent(connection, event, previousHash, recordHash);
                advanceHead(connection, event, recordHash);
                seededEvents.add(new SeededEvent(event, HexFormat.of().formatHex(recordHash)));
            }

            LegalHoldSeed legalHold = legalHold(eventSeeds);
            List<Epoch> eligibleDispositionUnits =
                    RETENTION_CLASSES.stream()
                            .map(retentionClass -> new Epoch(retentionClass, PERIODS.getFirst()))
                            .toList();
            Manifest manifest =
                    new Manifest(
                            TENANT_ID,
                            List.copyOf(seededEvents),
                            List.of(legalHold),
                            eligibleDispositionUnits,
                            List.of(
                                    Instant.parse("2026-02-01T00:00:00Z"),
                                    Instant.parse("2026-03-01T00:00:00Z")));
            if (managedTransaction) {
                connection.commit();
            }
            return manifest;
        } catch (RuntimeException | SQLException exception) {
            if (managedTransaction) {
                connection.rollback();
            } else {
                connection.rollback(savepoint);
            }
            throw exception;
        } finally {
            if (managedTransaction) {
                connection.setAutoCommit(true);
            }
        }
    }

    private static void requireUnseededTenant(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT count(*) FROM audit.audit_event WHERE tenant_id = ?")) {
            statement.setObject(1, TENANT_ID);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                if (rows.getLong(1) != 0) {
                    throw new IllegalStateException("Audit fixture tenant is already seeded");
                }
            }
        }
    }

    private static void provisionTopology(Connection connection) throws SQLException {
        try (PreparedStatement month =
                        connection.prepareStatement("SELECT audit.provision_audit_month(?)");
                PreparedStatement heads =
                        connection.prepareStatement(
                                "SELECT audit.provision_audit_epoch_heads(?, ?, ?, ?, ?)")) {
            for (LocalDate period : PERIODS) {
                month.setDate(1, Date.valueOf(period));
                month.execute();
                for (String retentionClass : RETENTION_CLASSES) {
                    heads.setObject(1, TENANT_ID);
                    heads.setString(2, retentionClass);
                    heads.setDate(3, Date.valueOf(period));
                    heads.setInt(4, SHARD_COUNT);
                    heads.setShort(5, (short) 1);
                    heads.execute();
                }
            }
        }
    }

    private static List<EventSeed> eventSeeds() {
        var events = new ArrayList<EventSeed>();
        List<List<Instant>> occurrences =
                List.of(
                        List.of(
                                Instant.parse("2026-01-31T23:59:58Z"),
                                Instant.parse("2026-01-31T23:59:59.999999Z")),
                        List.of(
                                Instant.parse("2026-02-01T00:00:00Z"),
                                Instant.parse("2026-02-28T23:59:59.999999Z")),
                        List.of(
                                Instant.parse("2026-03-01T00:00:00Z"),
                                Instant.parse("2026-03-01T00:00:01Z")));
        for (int classIndex = 0; classIndex < RETENTION_CLASSES.size(); classIndex++) {
            String retentionClass = RETENTION_CLASSES.get(classIndex);
            for (int periodIndex = 0; periodIndex < PERIODS.size(); periodIndex++) {
                for (int shardId = 0; shardId < SHARD_COUNT; shardId++) {
                    String discriminator = retentionClass + ":" + periodIndex + ":" + shardId;
                    UUID eventId =
                            UUID.nameUUIDFromBytes(discriminator.getBytes(StandardCharsets.UTF_8));
                    long policyVersion = periodIndex == 1 ? 2 : 1;
                    String policyKey =
                            policyVersion == 1
                                    ? "audit.fixture.baseline"
                                    : "audit.fixture.longest-wins";
                    Instant occurredAt = occurrences.get(periodIndex).get(shardId);
                    events.add(
                            new EventSeed(
                                    eventId,
                                    retentionClass,
                                    PERIODS.get(periodIndex),
                                    shardId,
                                    occurredAt,
                                    policyKey,
                                    policyVersion,
                                    occurredAt,
                                    retentionUntil(retentionClass, occurredAt),
                                    "fixture-entity-" + classIndex + "-" + shardId));
                }
            }
        }
        return List.copyOf(events);
    }

    private static Instant retentionUntil(String retentionClass, Instant start) {
        long days =
                switch (retentionClass) {
                    case "RESULT_CORRECTION_EVIDENCE" -> 2_555;
                    case "RESULT_PUBLICATION_EVIDENCE" -> 1_825;
                    case "PIN_SECURITY_EVENT" -> 365;
                    case "GENERAL_AUDIT_EVENT" -> 180;
                    default -> throw new IllegalArgumentException("Unknown retention class");
                };
        return start.plusSeconds(days * 24L * 60L * 60L);
    }

    private static byte[] currentHead(Connection connection, EventSeed event) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        """
                        SELECT head_hash
                        FROM audit.audit_chain_head
                        WHERE tenant_id = ?
                          AND retention_class = ?
                          AND period = ?
                          AND shard_id = ?
                          AND seq = 0
                        """)) {
            statement.setObject(1, TENANT_ID);
            statement.setString(2, event.retentionClass());
            statement.setDate(3, Date.valueOf(event.period()));
            statement.setInt(4, event.shardId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Missing pre-provisioned fixture chain head");
                }
                return rows.getBytes(1);
            }
        }
    }

    private static void insertEvent(
            Connection connection, EventSeed event, byte[] previousHash, byte[] recordHash)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        """
                        INSERT INTO audit.audit_event (
                            audit_event_id, event_type, entity_type, entity_id,
                            actor_type, actor_id, system_actor_name, tenant_id,
                            occurred_at, correlation_id, retention_class,
                            retention_policy_key, retention_policy_version, retention_until,
                            period, shard_id, seq, prev_hash, record_hash,
                            hash_algo_version, payload
                        ) VALUES (
                            ?, 'audit.FIXTURE_EVENT.v1', 'fixture', ?,
                            'SYSTEM', 'audit-fixture', 'audit-fixture', ?,
                            ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 1,
                            CAST(? AS jsonb)
                        )
                        """)) {
            statement.setObject(1, event.eventId());
            statement.setString(2, event.entityId());
            statement.setObject(3, TENANT_ID);
            statement.setTimestamp(4, Timestamp.from(event.occurredAt()));
            statement.setString(5, CORRELATION_ID);
            statement.setString(6, event.retentionClass());
            statement.setString(7, event.policyKey());
            statement.setLong(8, event.policyVersion());
            statement.setTimestamp(9, Timestamp.from(event.retentionUntil()));
            statement.setDate(10, Date.valueOf(event.period()));
            statement.setInt(11, event.shardId());
            statement.setBytes(12, previousHash);
            statement.setBytes(13, recordHash);
            statement.setString(
                    14,
                    "{\"fixture_event_id\":\""
                            + event.eventId()
                            + "\",\"policy_version\":"
                            + event.policyVersion()
                            + "}");
            statement.executeUpdate();
        }
    }

    private static void advanceHead(Connection connection, EventSeed event, byte[] recordHash)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        """
                        UPDATE audit.audit_chain_head
                        SET seq = 1, head_hash = ?, updated_at = ?
                        WHERE tenant_id = ?
                          AND retention_class = ?
                          AND period = ?
                          AND shard_id = ?
                          AND seq = 0
                        """)) {
            statement.setBytes(1, recordHash);
            statement.setTimestamp(2, Timestamp.from(event.occurredAt()));
            statement.setObject(3, TENANT_ID);
            statement.setString(4, event.retentionClass());
            statement.setDate(5, Date.valueOf(event.period()));
            statement.setInt(6, event.shardId());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Fixture chain head did not advance exactly once");
            }
        }
    }

    private static LegalHoldSeed legalHold(List<EventSeed> events) {
        EventSeed heldEvent =
                events.stream()
                        .filter(event -> event.retentionClass().equals(HELD_RETENTION_CLASS))
                        .filter(event -> event.period().equals(HELD_PERIOD))
                        .filter(event -> event.shardId() == 1)
                        .findFirst()
                        .orElseThrow();
        return new LegalHoldSeed(
                "hold-audit-fixture-001",
                heldEvent.eventId(),
                new Epoch(heldEvent.retentionClass(), heldEvent.period()),
                heldEvent.retentionStartedAt(),
                Instant.parse("2026-03-15T12:00:00Z"),
                heldEvent.policyKey(),
                heldEvent.policyVersion());
    }

    private static byte[] fixtureRecordHash(byte[] previousHash, EventSeed event) {
        MessageDigest digest = sha256();
        digest.update(previousHash);
        digest.update("meldtech.audit.fixture.record.v1\0".getBytes(StandardCharsets.UTF_8));
        digest.update(event.eventId().toString().getBytes(StandardCharsets.UTF_8));
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    @FunctionalInterface
    public interface RecordHashFunction {
        byte[] hash(byte[] previousHash, EventSeed event);
    }

    public record Epoch(String retentionClass, LocalDate period) {}

    public record EventSeed(
            UUID eventId,
            String retentionClass,
            LocalDate period,
            int shardId,
            Instant occurredAt,
            String policyKey,
            long policyVersion,
            Instant retentionStartedAt,
            Instant retentionUntil,
            String entityId) {}

    public record SeededEvent(EventSeed event, String recordHashHex) {}

    public record LegalHoldSeed(
            String holdReference,
            UUID coveredEventId,
            Epoch promotedEpoch,
            Instant originalRetentionStartedAt,
            Instant detectedAt,
            String policyKey,
            long policyVersion) {}

    public record Manifest(
            UUID tenantId,
            List<SeededEvent> events,
            List<LegalHoldSeed> legalHolds,
            List<Epoch> eligibleDispositionUnits,
            List<Instant> utcMonthBoundaries) {}
}
