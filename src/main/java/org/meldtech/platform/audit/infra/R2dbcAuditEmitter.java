package org.meldtech.platform.audit.infra;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.meldtech.platform.audit.domain.AuditChainKey;
import org.meldtech.platform.audit.domain.AuditHash;
import org.meldtech.platform.audit.domain.AuditHashing;
import org.meldtech.platform.audit.domain.AuditPayloadPolicy;
import org.meldtech.platform.audit.domain.AuditShardAssignment;
import org.meldtech.platform.audit.domain.AuditShardCountView;
import org.meldtech.platform.audit.domain.CanonicalAuditEnvelope;
import org.meldtech.platform.audit.domain.CanonicalDocument;
import org.meldtech.platform.audit.domain.CanonicalJsonCodec;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.audit.domain.ResolvedRetention;
import org.meldtech.platform.audit.domain.RetentionResolver;
import org.meldtech.platform.platform.api.TransactionalConnection;
import org.meldtech.platform.shared.kernel.audit.AuditEmitter;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.identity.IdGenerator;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

final class R2dbcAuditEmitter implements AuditEmitter {

    private final RetentionResolver retentionResolver;
    private final AuditShardCountView shardCounts;
    private final IdGenerator eventIds;
    private final CanonicalJsonCodec codec;
    private final AuditAppendStore appendStore;

    R2dbcAuditEmitter(
            RetentionResolver retentionResolver,
            AuditShardCountView shardCounts,
            IdGenerator eventIds,
            CanonicalJsonCodec codec,
            AuditAppendStore appendStore) {
        this.retentionResolver = Objects.requireNonNull(retentionResolver, "retentionResolver");
        this.shardCounts = Objects.requireNonNull(shardCounts, "shardCounts");
        this.eventIds = Objects.requireNonNull(eventIds, "eventIds");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.appendStore = Objects.requireNonNull(appendStore, "appendStore");
    }

    @Override
    public Publisher<Void> emit(AuditEvent event, ActorContext actor, Instant occurredAt) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(occurredAt, "occurredAt");
        return Mono.defer(
                () -> {
                    AuditPayloadPolicy.requireSecretFree(event.payload());
                    TenantId tenantId =
                            actor.tenantId()
                                    .orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "Tenant audit emission requires a tenant actor context"));
                    ResolvedRetention retention = retentionResolver.resolve(event, occurredAt);
                    EpochIdentity epoch =
                            EpochIdentity.from(retention.retentionClass(), occurredAt);
                    int shardCount = shardCounts.shardCount(tenantId, epoch.period());
                    AuditChainKey chain =
                            new AuditChainKey(
                                    tenantId,
                                    epoch,
                                    AuditShardAssignment.shardFor(event.entity(), shardCount),
                                    shardCount);
                    String payloadJson =
                            new String(
                                    codec.encode(event.payload()).bytes(), StandardCharsets.UTF_8);
                    return TransactionalConnection.current()
                            .flatMap(
                                    connection ->
                                            appendStore
                                                    .lockHead(connection, chain)
                                                    .flatMap(
                                                            head ->
                                                                    append(
                                                                            connection,
                                                                            event,
                                                                            actor,
                                                                            occurredAt,
                                                                            retention,
                                                                            chain,
                                                                            payloadJson,
                                                                            head)));
                });
    }

    private Mono<Void> append(
            TransactionalConnection connection,
            AuditEvent event,
            ActorContext actor,
            Instant occurredAt,
            ResolvedRetention retention,
            AuditChainKey chain,
            String payloadJson,
            LockedChainHead head) {
        if (head.headHash().hashAlgorithmVersion() != CanonicalJsonCodec.HASH_ALGORITHM_VERSION) {
            return Mono.error(new IllegalStateException("Unsupported audit chain hash version"));
        }
        long sequence = Math.addExact(head.sequence(), 1);
        CanonicalDocument envelope =
                codec.encode(
                        CanonicalAuditEnvelope.create(
                                event,
                                actor,
                                occurredAt,
                                retention,
                                chain,
                                sequence,
                                head.headHash().hashAlgorithmVersion()));
        AuditHash recordHash = AuditHashing.recordHash(head.headHash(), envelope);
        UUID eventId = Objects.requireNonNull(eventIds.generate(), "eventIds generated null");
        PreparedAuditRecord record =
                new PreparedAuditRecord(
                        eventId,
                        event,
                        actor,
                        occurredAt,
                        retention,
                        chain,
                        sequence,
                        head.headHash(),
                        recordHash,
                        payloadJson);
        return appendStore.appendAndAdvance(connection, head, record);
    }
}
