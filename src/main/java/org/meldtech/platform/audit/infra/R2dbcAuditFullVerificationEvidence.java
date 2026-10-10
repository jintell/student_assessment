package org.meldtech.platform.audit.infra;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.meldtech.platform.audit.application.AuditCheckpointAnchor;
import org.meldtech.platform.audit.application.AuditEpochSealRepository;
import org.meldtech.platform.audit.application.AuditFullVerificationEvidence;
import org.meldtech.platform.audit.application.OpenAuditChain;
import org.meldtech.platform.audit.application.TenantRootEvidence;
import org.meldtech.platform.audit.domain.AuditChainKey;
import org.meldtech.platform.audit.domain.AuditChainRecord;
import org.meldtech.platform.audit.domain.AuditCheckpointTail;
import org.meldtech.platform.audit.domain.AuditHash;
import org.meldtech.platform.audit.domain.AuditHashing;
import org.meldtech.platform.audit.domain.AuditSignature;
import org.meldtech.platform.audit.domain.CanonicalDocument;
import org.meldtech.platform.audit.domain.CanonicalJsonCodec;
import org.meldtech.platform.audit.domain.DerivedEpochRoot;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.audit.domain.EpochSealMaterial;
import org.meldtech.platform.audit.domain.ShardSealMaterial;
import org.meldtech.platform.audit.domain.ShardSequenceRange;
import org.meldtech.platform.audit.domain.SignedEpochSeal;
import org.meldtech.platform.audit.domain.UnsignedAuditCheckpoint;
import org.meldtech.platform.audit.domain.UnsignedEpochSeal;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ArrayValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.BooleanValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.DecimalValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.InstantValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.IntegerValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.NullValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Tenant-scoped PostgreSQL source for full audit verification. */
public final class R2dbcAuditFullVerificationEvidence implements AuditFullVerificationEvidence {

    private static final int ROOT_READ_RETRIES = 3;

    private static final String CHAINS_SQL =
            """
            SELECT head.retention_class, head.period, head.shard_id, head.shard_count,
                   head.seq, head.head_hash, head.hash_algo_version
            FROM audit.audit_chain_head head
            WHERE head.tenant_id = $1
              AND EXISTS (
                  SELECT 1 FROM audit.audit_event event
                  WHERE event.tenant_id = head.tenant_id
                    AND event.retention_class = head.retention_class
                    AND event.period = head.period
                    AND event.shard_id = head.shard_id)
            ORDER BY head.period, head.retention_class, head.shard_id
            """;

    private static final String RECORDS_SQL =
            """
            SELECT event_type, entity_type, entity_id, actor_type, actor_id,
                   system_actor_name, occurred_at, correlation_id, retention_class,
                   retention_policy_key, retention_policy_version, retention_until,
                   period, shard_id, seq, prev_hash, record_hash, hash_algo_version,
                   payload::text AS payload_json
            FROM audit.audit_event
            WHERE tenant_id = $1 AND retention_class = $2 AND period = $3 AND shard_id = $4
            ORDER BY seq
            """;

    private static final String CHECKPOINTS_SQL =
            """
            SELECT seq_start, seq_end, head_hash, hash_algo_version,
                   event_occurred_from, event_occurred_to,
                   signing_key_version, signature_algorithm, signature,
                   signature_request_id, signed_at
            FROM audit.audit_chain_checkpoint
            WHERE tenant_id = $1 AND retention_class = $2 AND period = $3 AND shard_id = $4
            ORDER BY seq_end
            """;

    private static final String SEALS_SQL =
            """
            SELECT retention_class, period, root_seq, previous_root_hash, epoch_root,
                   shard_count, per_shard_counts::text AS counts_json,
                   sequence_ranges::text AS ranges_json, hash_algo_version,
                   signing_key_version, signature_algorithm, signature,
                   signature_request_id, signed_at
            FROM audit.audit_chain_seal
            WHERE tenant_id = $1
            ORDER BY root_seq
            """;

    private static final String SHARD_HEADS_SQL =
            """
            SELECT shard_id, shard_count, seq, head_hash, hash_algo_version
            FROM audit.audit_chain_head
            WHERE tenant_id = $1 AND retention_class = $2 AND period = $3
            ORDER BY shard_id
            """;

    private final ConnectionFactory connectionFactory;
    private final TenantId tenantId;
    private final CanonicalJsonCodec codec;
    private final ObjectMapper objectMapper;
    private final AuditEpochSealRepository rootHeads;

    public R2dbcAuditFullVerificationEvidence(
            ConnectionFactory connectionFactory, TenantId tenantId, CanonicalJsonCodec codec) {
        this(connectionFactory, tenantId, codec, new ObjectMapper());
    }

    R2dbcAuditFullVerificationEvidence(
            ConnectionFactory connectionFactory,
            TenantId tenantId,
            CanonicalJsonCodec codec,
            ObjectMapper objectMapper) {
        this(
                connectionFactory,
                tenantId,
                codec,
                objectMapper,
                new R2dbcAuditEpochSealRepository(connectionFactory, objectMapper));
    }

    R2dbcAuditFullVerificationEvidence(
            ConnectionFactory connectionFactory,
            TenantId tenantId,
            CanonicalJsonCodec codec,
            ObjectMapper objectMapper,
            AuditEpochSealRepository rootHeads) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.rootHeads = Objects.requireNonNull(rootHeads, "rootHeads");
    }

    @Override
    public Flux<OpenAuditChain> retainedChains() {
        return chains(null);
    }

    public Flux<OpenAuditChain> retainedChains(EpochIdentity epoch) {
        return chains(Objects.requireNonNull(epoch, "epoch"));
    }

    @Override
    public Flux<AuditChainRecord> records(OpenAuditChain chain) {
        Objects.requireNonNull(chain, "chain");
        AuditChainKey key = chain.key();
        if (!key.tenantId().equals(tenantId)) {
            return Flux.error(new IllegalArgumentException("chain belongs to another tenant"));
        }
        return Flux.usingWhen(
                Mono.from(connectionFactory.create()),
                connection -> records(connection, key),
                connection -> Mono.from(connection.close()),
                (connection, failure) -> Mono.from(connection.close()),
                connection -> Mono.from(connection.close()));
    }

    @Override
    public Flux<TenantRootEvidence> tenantRootChains() {
        return rootEvidence().flux();
    }

    public Mono<TenantRootEvidence> rootEvidence() {
        return rootEvidence(ROOT_READ_RETRIES);
    }

    private Mono<TenantRootEvidence> rootEvidence(int retriesRemaining) {
        // Seals are immutable; equal heads bracket a complete, stable root-chain read.
        return rootHeads
                .readRootHead(tenantId)
                .flatMap(
                        observed ->
                                readSealsAndCurrentHead()
                                        .flatMap(
                                                loaded -> {
                                                    if (observed.equals(loaded.currentHead())) {
                                                        return Mono.just(loaded);
                                                    }
                                                    if (retriesRemaining == 0) {
                                                        return Mono.error(
                                                                new IllegalStateException(
                                                                        "audit root advancing; retry verification"));
                                                    }
                                                    return rootEvidence(retriesRemaining - 1);
                                                }));
    }

    private Mono<TenantRootEvidence> readSealsAndCurrentHead() {
        return loadSeals()
                .flatMap(
                        seals ->
                                rootHeads
                                        .readRootHead(tenantId)
                                        .map(
                                                current ->
                                                        new TenantRootEvidence(
                                                                tenantId, seals, current)));
    }

    public Mono<SignedEpochSeal> seal(EpochIdentity epoch) {
        Objects.requireNonNull(epoch, "epoch");
        return loadSeals()
                .flatMapMany(Flux::fromIterable)
                .filter(candidate -> candidate.evidence().material().epoch().equals(epoch))
                .singleOrEmpty()
                .switchIfEmpty(Mono.error(new IllegalStateException("audit epoch is not sealed")));
    }

    private Flux<OpenAuditChain> chains(@Nullable EpochIdentity epoch) {
        return Flux.usingWhen(
                Mono.from(connectionFactory.create()),
                connection -> {
                    Statement statement =
                            connection
                                    .createStatement(CHAINS_SQL)
                                    .bind(0, UUID.fromString(tenantId.toString()));
                    return Flux.from(statement.execute())
                            .flatMap(result -> result.map((row, metadata) -> mapChainHead(row)))
                            .filter(chain -> epoch == null || chain.key().epoch().equals(epoch))
                            .collectList()
                            .flatMapMany(
                                    chains ->
                                            Flux.fromIterable(chains)
                                                    .concatMap(
                                                            chain ->
                                                                    withCheckpoints(
                                                                            connection, chain)));
                },
                connection -> Mono.from(connection.close()),
                (connection, failure) -> Mono.from(connection.close()),
                connection -> Mono.from(connection.close()));
    }

    private Mono<OpenAuditChain> withCheckpoints(Connection connection, OpenAuditChain chain) {
        AuditChainKey key = chain.key();
        Statement statement = bindEpoch(connection.createStatement(CHECKPOINTS_SQL), key);
        return Flux.from(statement.execute())
                .flatMap(result -> result.map((row, metadata) -> mapCheckpoint(row, key)))
                .collectList()
                .map(
                        checkpoints ->
                                new OpenAuditChain(
                                        key,
                                        chain.seed(),
                                        chain.committedSequence(),
                                        chain.committedHead(),
                                        checkpoints));
    }

    private Flux<AuditChainRecord> records(Connection connection, AuditChainKey key) {
        Statement statement = bindEpoch(connection.createStatement(RECORDS_SQL), key);
        return Flux.from(statement.execute())
                .flatMap(result -> result.map((row, metadata) -> mapRecord(row)));
    }

    private Mono<List<SignedEpochSeal>> loadSeals() {
        return withConnection(
                        connection -> {
                            Statement statement =
                                    connection
                                            .createStatement(SEALS_SQL)
                                            .bind(0, UUID.fromString(tenantId.toString()));
                            return Flux.from(statement.execute())
                                    .flatMap(
                                            result ->
                                                    result.map(
                                                            (row, metadata) -> mapStoredSeal(row)))
                                    .collectList();
                        })
                .flatMapMany(Flux::fromIterable)
                .concatMap(this::completeSeal)
                .collectList();
    }

    private Mono<SignedEpochSeal> completeSeal(StoredSeal stored) {
        return loadShardMaterial(stored.epoch())
                .map(
                        shards -> {
                            EpochSealMaterial material =
                                    new EpochSealMaterial(
                                            tenantId,
                                            stored.epoch(),
                                            stored.shardCount(),
                                            stored.hashVersion(),
                                            stored.previousRoot(),
                                            stored.rootSequence(),
                                            shards);
                            DerivedEpochRoot root =
                                    new DerivedEpochRoot(
                                            stored.epochRoot(), stored.counts(), stored.ranges());
                            return new SignedEpochSeal(
                                    new UnsignedEpochSeal(material, root, stored.signedAt()),
                                    stored.signature());
                        });
    }

    private Mono<List<ShardSealMaterial>> loadShardMaterial(EpochIdentity epoch) {
        return withConnection(
                connection -> {
                    Statement statement =
                            connection
                                    .createStatement(SHARD_HEADS_SQL)
                                    .bind(0, UUID.fromString(tenantId.toString()))
                                    .bind(1, epoch.retentionClass().name())
                                    .bind(2, epoch.period().atDay(1));
                    return Flux.from(statement.execute())
                            .flatMap(result -> result.map((row, metadata) -> mapShardMaterial(row)))
                            .collectList();
                });
    }

    private OpenAuditChain mapChainHead(Row row) {
        EpochIdentity epoch = epoch(row);
        int shardId = required(row, "shard_id", Integer.class);
        int shardCount = required(row, "shard_count", Integer.class);
        short version = required(row, "hash_algo_version", Short.class);
        AuditChainKey key = new AuditChainKey(tenantId, epoch, shardId, shardCount);
        return new OpenAuditChain(
                key,
                AuditHashing.chainSeed(
                        tenantId,
                        epoch.retentionClass(),
                        epoch.period(),
                        shardId,
                        shardCount,
                        codec),
                required(row, "seq", Long.class),
                new AuditHash(version, required(row, "head_hash", byte[].class)),
                List.of());
    }

    private AuditChainRecord mapRecord(Row row) {
        short version = required(row, "hash_algo_version", Short.class);
        CanonicalDocument event = codec.encode(canonicalEnvelope(row));
        if (event.hashAlgorithmVersion() != version) {
            throw new IllegalStateException("unsupported stored audit hash version");
        }
        return new AuditChainRecord(
                required(row, "seq", Long.class),
                new AuditHash(version, required(row, "prev_hash", byte[].class)),
                new AuditHash(version, required(row, "record_hash", byte[].class)),
                event);
    }

    private ObjectValue canonicalEnvelope(Row row) {
        Map<String, CanonicalValue> fields = new LinkedHashMap<>();
        fields.put("event_type", text(row, "event_type"));
        fields.put("entity_type", text(row, "entity_type"));
        fields.put("entity_id", text(row, "entity_id"));
        fields.put("actor_type", text(row, "actor_type"));
        fields.put("actor_id", text(row, "actor_id"));
        fields.put("system_actor_name", nullableText(row, "system_actor_name"));
        fields.put("tenant_id", new StringValue(tenantId.toString()));
        fields.put(
                "occurred_at",
                new InstantValue(required(row, "occurred_at", OffsetDateTime.class).toInstant()));
        fields.put("correlation_id", text(row, "correlation_id"));
        fields.put("retention_class", text(row, "retention_class"));
        fields.put("retention_policy_key", text(row, "retention_policy_key"));
        fields.put(
                "retention_policy_version",
                new IntegerValue(required(row, "retention_policy_version", Long.class)));
        OffsetDateTime retainedUntil = row.get("retention_until", OffsetDateTime.class);
        fields.put(
                "retention_until",
                retainedUntil == null
                        ? NullValue.INSTANCE
                        : new InstantValue(retainedUntil.toInstant()));
        fields.put(
                "period",
                new StringValue(
                        YearMonth.from(required(row, "period", LocalDate.class)).toString()));
        fields.put("shard_id", new IntegerValue(required(row, "shard_id", Integer.class)));
        fields.put("seq", new IntegerValue(required(row, "seq", Long.class)));
        fields.put(
                "hash_algo_version",
                new IntegerValue(required(row, "hash_algo_version", Short.class)));
        fields.put("payload", jsonValue(required(row, "payload_json", String.class)));
        return new ObjectValue(fields);
    }

    private AuditCheckpointAnchor mapCheckpoint(Row row, AuditChainKey key) {
        long start = required(row, "seq_start", Long.class);
        long end = required(row, "seq_end", Long.class);
        short version = required(row, "hash_algo_version", Short.class);
        AuditHash head = new AuditHash(version, required(row, "head_hash", byte[].class));
        var signedAt = required(row, "signed_at", OffsetDateTime.class).toInstant();
        AuditCheckpointTail tail =
                new AuditCheckpointTail(
                        key,
                        start - 1,
                        end,
                        head,
                        Optional.of(
                                required(row, "event_occurred_from", OffsetDateTime.class)
                                        .toInstant()),
                        Optional.of(
                                required(row, "event_occurred_to", OffsetDateTime.class)
                                        .toInstant()));
        AuditSignature signature = signature(row, signedAt);
        return new AuditCheckpointAnchor(
                end,
                head,
                new UnsignedAuditCheckpoint(tail, signedAt).signingMessage(codec),
                signature);
    }

    private StoredSeal mapStoredSeal(Row row) {
        short version = required(row, "hash_algo_version", Short.class);
        var signedAt = required(row, "signed_at", OffsetDateTime.class).toInstant();
        return new StoredSeal(
                epoch(row),
                required(row, "root_seq", Long.class),
                new AuditHash(version, required(row, "previous_root_hash", byte[].class)),
                new AuditHash(version, required(row, "epoch_root", byte[].class)),
                required(row, "shard_count", Integer.class),
                counts(required(row, "counts_json", String.class)),
                ranges(required(row, "ranges_json", String.class)),
                version,
                signedAt,
                signature(row, signedAt));
    }

    private static ShardSealMaterial mapShardMaterial(Row row) {
        int shardId = required(row, "shard_id", Integer.class);
        long sequence = required(row, "seq", Long.class);
        if (sequence == 0) {
            return ShardSealMaterial.empty(shardId);
        }
        short version = required(row, "hash_algo_version", Short.class);
        return ShardSealMaterial.populated(
                shardId,
                sequence,
                new AuditHash(version, required(row, "head_hash", byte[].class)));
    }

    private AuditSignature signature(Row row, java.time.Instant signedAt) {
        return new AuditSignature(
                required(row, "signing_key_version", String.class),
                required(row, "signature_algorithm", String.class),
                required(row, "signature", byte[].class),
                required(row, "signature_request_id", String.class),
                signedAt);
    }

    private List<Long> counts(String json) {
        JsonNode node = json(json);
        List<Long> counts = new ArrayList<>();
        for (JsonNode value : node.values()) {
            counts.add(value.asLong());
        }
        return List.copyOf(counts);
    }

    private List<ShardSequenceRange> ranges(String json) {
        JsonNode node = json(json);
        List<ShardSequenceRange> ranges = new ArrayList<>();
        for (JsonNode value : node.values()) {
            ranges.add(
                    new ShardSequenceRange(
                            value.get("shard_id").asInt(),
                            optionalLong(value.get("seq_start")),
                            optionalLong(value.get("seq_end"))));
        }
        return List.copyOf(ranges);
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored audit JSON is invalid", exception);
        }
    }

    private CanonicalValue jsonValue(String value) {
        return canonical(json(value));
    }

    private static CanonicalValue canonical(JsonNode node) {
        if (node.isObject()) {
            Map<String, CanonicalValue> fields = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                fields.put(property.getKey(), canonical(property.getValue()));
            }
            return new ObjectValue(fields);
        }
        if (node.isArray()) {
            List<CanonicalValue> values = new ArrayList<>();
            for (JsonNode value : node.values()) {
                values.add(canonical(value));
            }
            return new ArrayValue(values);
        }
        if (node.isString()) {
            return new StringValue(node.stringValue());
        }
        if (node.isIntegralNumber()) {
            return new IntegerValue(node.bigIntegerValue());
        }
        if (node.isNumber()) {
            return new DecimalValue(node.decimalValue());
        }
        if (node.isBoolean()) {
            return new BooleanValue(node.asBoolean());
        }
        if (node.isNull()) {
            return NullValue.INSTANCE;
        }
        throw new IllegalStateException("stored audit JSON contains an unsupported value");
    }

    private static OptionalLong optionalLong(JsonNode value) {
        return value.isNull() ? OptionalLong.empty() : OptionalLong.of(value.asLong());
    }

    private static Statement bindEpoch(Statement statement, AuditChainKey key) {
        return statement
                .bind(0, UUID.fromString(key.tenantId().toString()))
                .bind(1, key.epoch().retentionClass().name())
                .bind(2, key.epoch().period().atDay(1))
                .bind(3, key.shardId());
    }

    private EpochIdentity epoch(Row row) {
        return new EpochIdentity(
                RetentionClass.valueOf(required(row, "retention_class", String.class)),
                YearMonth.from(required(row, "period", LocalDate.class)));
    }

    private static StringValue text(Row row, String column) {
        return new StringValue(required(row, column, String.class));
    }

    private static CanonicalValue nullableText(Row row, String column) {
        String value = row.get(column, String.class);
        return value == null ? NullValue.INSTANCE : new StringValue(value);
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

    private record StoredSeal(
            EpochIdentity epoch,
            long rootSequence,
            AuditHash previousRoot,
            AuditHash epochRoot,
            int shardCount,
            List<Long> counts,
            List<ShardSequenceRange> ranges,
            short hashVersion,
            java.time.Instant signedAt,
            AuditSignature signature) {}
}
