package org.meldtech.platform.audit.infra;

import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.meldtech.platform.audit.domain.AuditChainKey;
import org.meldtech.platform.audit.domain.AuditHash;
import org.meldtech.platform.platform.api.AuditStatementKind;
import org.meldtech.platform.platform.api.TransactionalConnection;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

final class R2dbcAuditAppendRepository implements AuditAppendStore {

    static final String LOCK_HEAD_SQL =
            """
            SELECT seq, head_hash, hash_algo_version
            FROM audit.audit_chain_head
            WHERE tenant_id = $1
              AND retention_class = $2
              AND period = $3
              AND shard_id = $4
              AND shard_count = $5
            FOR UPDATE
            """;
    static final String APPEND_SQL =
            """
            WITH inserted_event AS MATERIALIZED (
                INSERT INTO audit.audit_event (
                    audit_event_id, event_type, entity_type, entity_id,
                    actor_type, actor_id, system_actor_name, tenant_id,
                    occurred_at, correlation_id, retention_class,
                    retention_policy_key, retention_policy_version, retention_until,
                    period, shard_id, seq, prev_hash, record_hash,
                    hash_algo_version, payload
                ) VALUES (
                    $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11,
                    $12, $13, $14, $15, $16, $17, $18, $19, $20, $21::jsonb
                )
                RETURNING 1
            ), advanced_head AS (
                UPDATE audit.audit_chain_head
                SET seq = $17,
                    head_hash = $19,
                    updated_at = $9
                WHERE tenant_id = $8
                  AND retention_class = $11
                  AND period = $15
                  AND shard_id = $16
                  AND shard_count = $22
                  AND seq = $23
                  AND head_hash = $18
                  AND EXISTS (SELECT 1 FROM inserted_event)
                RETURNING 1
            )
            SELECT
                (SELECT count(*) FROM inserted_event) AS inserted_count,
                (SELECT count(*) FROM advanced_head) AS updated_count
            """;

    @Override
    public Mono<LockedChainHead> lockHead(TransactionalConnection connection, AuditChainKey key) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(key, "key");
        connection.beginAuditFinalization();
        Statement statement =
                connection
                        .createAuditStatement(AuditStatementKind.LOCK_PREDECESSOR, LOCK_HEAD_SQL)
                        .bind(0, UUID.fromString(key.tenantId().toString()))
                        .bind(1, key.epoch().retentionClass().name())
                        .bind(
                                2,
                                LocalDate.of(
                                        key.epoch().period().getYear(),
                                        key.epoch().period().getMonth(),
                                        1))
                        .bind(3, key.shardId())
                        .bind(4, key.shardCount());
        return Flux.from(statement.execute())
                .flatMap(this::mapLockedHead)
                .singleOrEmpty()
                .switchIfEmpty(Mono.error(new AuditProvisioningException()));
    }

    @Override
    public Mono<Void> appendAndAdvance(
            TransactionalConnection connection,
            LockedChainHead lockedHead,
            PreparedAuditRecord record) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(lockedHead, "lockedHead");
        Objects.requireNonNull(record, "record");
        Statement statement =
                bindAppend(
                        connection.createAuditStatement(
                                AuditStatementKind.APPEND_AND_ADVANCE, APPEND_SQL),
                        record,
                        lockedHead);
        return Flux.from(statement.execute())
                .flatMap(
                        result ->
                                result.map(
                                        (row, metadata) ->
                                                new AppendCounts(
                                                        row.get("inserted_count", Long.class),
                                                        row.get("updated_count", Long.class))))
                .single()
                .flatMap(
                        counts ->
                                counts.insertedRows() == 1 && counts.updatedRows() == 1
                                        ? Mono.empty()
                                        : Mono.error(
                                                new AuditAppendIntegrityException(
                                                        counts.insertedRows(),
                                                        counts.updatedRows())));
    }

    private static Statement bindAppend(
            Statement statement, PreparedAuditRecord record, LockedChainHead lockedHead) {
        statement
                .bind(0, record.auditEventId())
                .bind(1, record.event().eventType())
                .bind(2, record.event().entity().entityType())
                .bind(3, record.event().entity().entityId())
                .bind(4, record.actor().actorType().name())
                .bind(5, record.actor().actorId().toString());
        bindOptional(statement, 6, record.actor().systemActorName().map(Enum::name), String.class);
        statement
                .bind(7, UUID.fromString(record.chain().tenantId().toString()))
                .bind(8, record.occurredAt())
                .bind(9, record.actor().correlationId().toString())
                .bind(10, record.retention().retentionClass().name())
                .bind(11, record.retention().policyKey())
                .bind(12, record.retention().policyVersion());
        bindOptional(
                statement,
                13,
                record.retention().horizon().retainedUntil(),
                java.time.Instant.class);
        statement
                .bind(
                        14,
                        LocalDate.of(
                                record.chain().epoch().period().getYear(),
                                record.chain().epoch().period().getMonth(),
                                1))
                .bind(15, record.chain().shardId())
                .bind(16, record.sequence())
                .bind(17, record.previousHash().bytes())
                .bind(18, record.recordHash().bytes())
                .bind(19, record.recordHash().hashAlgorithmVersion())
                .bind(20, record.payloadJson())
                .bind(21, record.chain().shardCount())
                .bind(22, lockedHead.sequence());
        return statement;
    }

    private static <T> void bindOptional(
            Statement statement, int index, Optional<T> value, Class<T> valueType) {
        value.ifPresentOrElse(
                present -> statement.bind(index, present),
                () -> statement.bindNull(index, valueType));
    }

    private Flux<LockedChainHead> mapLockedHead(Result result) {
        return Flux.from(
                result.map(
                        (row, metadata) ->
                                new LockedChainHead(
                                        row.get("seq", Long.class),
                                        new AuditHash(
                                                row.get("hash_algo_version", Short.class),
                                                row.get("head_hash", byte[].class)))));
    }

    private record AppendCounts(long insertedRows, long updatedRows) {}
}
