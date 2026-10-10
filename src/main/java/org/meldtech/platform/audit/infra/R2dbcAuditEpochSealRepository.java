package org.meldtech.platform.audit.infra;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.meldtech.platform.audit.application.AuditEpochSealRepository;
import org.meldtech.platform.audit.domain.AuditHash;
import org.meldtech.platform.audit.domain.AuditRootHead;
import org.meldtech.platform.audit.domain.CanonicalJsonCodec;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.audit.domain.EpochSealMaterial;
import org.meldtech.platform.audit.domain.ShardSealMaterial;
import org.meldtech.platform.audit.domain.ShardSequenceRange;
import org.meldtech.platform.audit.domain.SignedEpochSeal;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL persistence for epoch sealing; each append is one atomic statement. */
public final class R2dbcAuditEpochSealRepository implements AuditEpochSealRepository {

    static final String READ_ROOT_HEAD_SQL =
            """
            SELECT root.root_seq,
                   root.root_head_hash,
                   COALESCE(seal.hash_algo_version, $2::smallint) AS hash_algo_version
            FROM audit.audit_chain_root_head root
            LEFT JOIN audit.audit_chain_seal seal
              ON seal.tenant_id = root.tenant_id
             AND seal.root_seq = root.root_seq
            WHERE root.tenant_id = $1
            """;

    static final String LOAD_EPOCH_MATERIAL_SQL =
            """
            SELECT shard_id, shard_count, seq, head_hash, hash_algo_version
            FROM audit.audit_chain_head
            WHERE tenant_id = $1
              AND retention_class = $2
              AND period = $3
            ORDER BY shard_id
            """;

    static final String TRUSTED_TIME_SQL = "SELECT clock_timestamp() AS signed_at";

    static final String APPEND_SEAL_SQL =
            """
            WITH advanced_root AS MATERIALIZED (
                UPDATE audit.audit_chain_root_head
                SET root_seq = $4,
                    root_head_hash = $6,
                    sealed_at = $15
                WHERE tenant_id = $1
                  AND root_seq = $16
                  AND root_head_hash = $17
                RETURNING tenant_id
            ), inserted_seal AS MATERIALIZED (
                INSERT INTO audit.audit_chain_seal (
                    tenant_id, retention_class, period, root_seq,
                    previous_root_hash, epoch_root, shard_count,
                    per_shard_counts, sequence_ranges, hash_algo_version,
                    signing_key_version, signature_algorithm, signature,
                    signature_request_id, signed_at
                )
                SELECT $1, $2, $3, $4, $5, $6, $7,
                       $8::jsonb, $9::jsonb, $10, $11, $12, $13, $14, $15
                FROM advanced_root
                RETURNING 1
            )
            SELECT (SELECT count(*) FROM advanced_root) AS advanced_count,
                   (SELECT count(*) FROM inserted_seal) AS inserted_count
            """;

    private final ConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;

    public R2dbcAuditEpochSealRepository(ConnectionFactory connectionFactory) {
        this(connectionFactory, new ObjectMapper());
    }

    R2dbcAuditEpochSealRepository(ConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public Mono<AuditRootHead> readRootHead(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return withConnection(
                connection -> {
                    Statement statement =
                            connection
                                    .createStatement(READ_ROOT_HEAD_SQL)
                                    .bind(0, UUID.fromString(tenantId.toString()))
                                    .bind(1, CanonicalJsonCodec.HASH_ALGORITHM_VERSION);
                    return Flux.from(statement.execute())
                            .flatMap(result -> result.map((row, metadata) -> mapRootHead(row)))
                            .singleOrEmpty()
                            .switchIfEmpty(
                                    Mono.error(
                                            new IllegalStateException(
                                                    "Audit root head is not provisioned")));
                });
    }

    @Override
    public Mono<EpochSealMaterial> loadEpochMaterial(
            TenantId tenantId, EpochIdentity epoch, AuditRootHead observedRootHead) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(observedRootHead, "observedRootHead");
        return withConnection(
                connection -> {
                    Statement statement =
                            connection
                                    .createStatement(LOAD_EPOCH_MATERIAL_SQL)
                                    .bind(0, UUID.fromString(tenantId.toString()))
                                    .bind(1, epoch.retentionClass().name())
                                    .bind(
                                            2,
                                            LocalDate.of(
                                                    epoch.period().getYear(),
                                                    epoch.period().getMonth(),
                                                    1));
                    return Flux.from(statement.execute())
                            .flatMap(result -> result.map((row, metadata) -> mapShardHead(row)))
                            .collectList()
                            .map(rows -> material(tenantId, epoch, observedRootHead, rows));
                });
    }

    @Override
    public Mono<Instant> trustedSigningTime() {
        return withConnection(
                connection ->
                        Flux.from(connection.createStatement(TRUSTED_TIME_SQL).execute())
                                .flatMap(
                                        result ->
                                                result.map(
                                                        (row, metadata) ->
                                                                required(
                                                                                row,
                                                                                "signed_at",
                                                                                OffsetDateTime
                                                                                        .class)
                                                                        .toInstant()))
                                .single());
    }

    @Override
    public Mono<Boolean> insertSealAndCompareAndSwap(
            SignedEpochSeal seal, AuditRootHead observedRootHead) {
        Objects.requireNonNull(seal, "seal");
        Objects.requireNonNull(observedRootHead, "observedRootHead");
        requireObservedRoot(seal, observedRootHead);
        return withConnection(
                connection -> {
                    Statement statement =
                            bindSeal(
                                    connection.createStatement(APPEND_SEAL_SQL),
                                    seal,
                                    observedRootHead);
                    return Flux.from(statement.execute())
                            .flatMap(result -> result.map((row, metadata) -> mapAppendCounts(row)))
                            .single()
                            .flatMap(R2dbcAuditEpochSealRepository::committed);
                });
    }

    private Statement bindSeal(
            Statement statement, SignedEpochSeal seal, AuditRootHead observedRootHead) {
        var evidence = seal.evidence();
        var material = evidence.material();
        var derived = evidence.derivedRoot();
        var signature = seal.signature();
        return statement
                .bind(0, UUID.fromString(material.tenantId().toString()))
                .bind(1, material.epoch().retentionClass().name())
                .bind(
                        2,
                        LocalDate.of(
                                material.epoch().period().getYear(),
                                material.epoch().period().getMonth(),
                                1))
                .bind(3, material.rootSequence())
                .bind(4, material.previousRootHash().bytes())
                .bind(5, derived.epochRoot().bytes())
                .bind(6, material.shardCount())
                .bind(7, json(derived.perShardCounts()))
                .bind(8, json(sequenceRanges(derived.sequenceRanges())))
                .bind(9, material.hashAlgorithmVersion())
                .bind(10, signature.keyVersion())
                .bind(11, signature.algorithm())
                .bind(12, signature.signature())
                .bind(13, signature.providerRequestId())
                .bind(14, evidence.signedAt())
                .bind(15, observedRootHead.sequence())
                .bind(16, observedRootHead.hash().bytes());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot serialize audit seal material", exception);
        }
    }

    private static List<Map<String, @Nullable Object>> sequenceRanges(
            List<ShardSequenceRange> ranges) {
        List<Map<String, @Nullable Object>> serialized = new ArrayList<>(ranges.size());
        for (ShardSequenceRange range : ranges) {
            Map<String, @Nullable Object> fields = new LinkedHashMap<>();
            fields.put("shard_id", range.shardId());
            fields.put("seq_start", optional(range.start()));
            fields.put("seq_end", optional(range.end()));
            serialized.add(fields);
        }
        return List.copyOf(serialized);
    }

    private static @Nullable Long optional(OptionalLong value) {
        return value.isPresent() ? value.orElseThrow() : null;
    }

    private static AuditRootHead mapRootHead(Row row) {
        short version = required(row, "hash_algo_version", Short.class);
        return new AuditRootHead(
                required(row, "root_seq", Long.class),
                new AuditHash(version, required(row, "root_head_hash", byte[].class)));
    }

    private static ShardHead mapShardHead(Row row) {
        short version = required(row, "hash_algo_version", Short.class);
        return new ShardHead(
                required(row, "shard_id", Integer.class),
                required(row, "shard_count", Integer.class),
                required(row, "seq", Long.class),
                new AuditHash(version, required(row, "head_hash", byte[].class)));
    }

    private static AppendCounts mapAppendCounts(Row row) {
        return new AppendCounts(
                required(row, "advanced_count", Long.class),
                required(row, "inserted_count", Long.class));
    }

    private static EpochSealMaterial material(
            TenantId tenantId,
            EpochIdentity epoch,
            AuditRootHead observedRootHead,
            List<ShardHead> rows) {
        if (rows.isEmpty()) {
            throw new IllegalStateException("Audit epoch heads are not provisioned");
        }
        int shardCount = rows.getFirst().shardCount();
        short hashVersion = rows.getFirst().headHash().hashAlgorithmVersion();
        if (rows.size() != shardCount
                || observedRootHead.hash().hashAlgorithmVersion() != hashVersion
                || rows.stream()
                        .anyMatch(
                                row ->
                                        row.shardCount() != shardCount
                                                || row.headHash().hashAlgorithmVersion()
                                                        != hashVersion)) {
            throw new IllegalStateException("Audit epoch head topology is inconsistent");
        }
        List<ShardSealMaterial> shards =
                rows.stream()
                        .map(
                                row ->
                                        row.sequence() == 0
                                                ? ShardSealMaterial.empty(row.shardId())
                                                : ShardSealMaterial.populated(
                                                        row.shardId(),
                                                        row.sequence(),
                                                        row.headHash()))
                        .toList();
        return new EpochSealMaterial(
                tenantId,
                epoch,
                shardCount,
                hashVersion,
                observedRootHead.hash(),
                observedRootHead.nextSequence(),
                shards);
    }

    private static void requireObservedRoot(SignedEpochSeal seal, AuditRootHead observedRootHead) {
        EpochSealMaterial material = seal.evidence().material();
        if (material.rootSequence() != observedRootHead.nextSequence()
                || !material.previousRootHash().equals(observedRootHead.hash())) {
            throw new IllegalArgumentException("Seal was not derived from the observed root head");
        }
    }

    private static Mono<Boolean> committed(AppendCounts counts) {
        if (counts.advancedRows() == 0 && counts.insertedRows() == 0) {
            return Mono.just(false);
        }
        if (counts.advancedRows() == 1 && counts.insertedRows() == 1) {
            return Mono.just(true);
        }
        return Mono.error(
                new IllegalStateException(
                        "Audit seal append changed an inconsistent number of rows: advanced="
                                + counts.advancedRows()
                                + ", inserted="
                                + counts.insertedRows()));
    }

    private <T> Mono<T> withConnection(Function<Connection, ? extends Publisher<T>> work) {
        return Mono.usingWhen(
                Mono.from(connectionFactory.create()),
                connection -> Mono.from(work.apply(connection)),
                connection -> Mono.from(connection.close()),
                (connection, failure) -> Mono.from(connection.close()),
                connection -> Mono.from(connection.close()));
    }

    private static <T> T required(Row row, String column, Class<T> type) {
        return Objects.requireNonNull(row.get(column, type), column);
    }

    private record ShardHead(int shardId, int shardCount, long sequence, AuditHash headHash) {}

    private record AppendCounts(long advancedRows, long insertedRows) {}
}
