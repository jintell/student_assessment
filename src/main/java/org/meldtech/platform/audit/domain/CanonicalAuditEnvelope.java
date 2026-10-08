package org.meldtech.platform.audit.domain;

import java.util.Map;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.InstantValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.IntegerValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.NullValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;
import org.meldtech.platform.shared.kernel.context.ActorContext;

public final class CanonicalAuditEnvelope {

    private CanonicalAuditEnvelope() {}

    public static ObjectValue create(
            AuditEvent event,
            ActorContext actor,
            java.time.Instant occurredAt,
            ResolvedRetention retention,
            AuditChainKey chain,
            long sequence,
            short hashAlgorithmVersion) {
        CanonicalValue systemActor =
                actor.systemActorName()
                        .<CanonicalValue>map(value -> new StringValue(value.name()))
                        .orElse(NullValue.INSTANCE);
        CanonicalValue retentionUntil =
                retention
                        .horizon()
                        .retainedUntil()
                        .<CanonicalValue>map(InstantValue::new)
                        .orElse(NullValue.INSTANCE);
        return new ObjectValue(
                Map.ofEntries(
                        Map.entry("event_type", new StringValue(event.eventType())),
                        Map.entry("entity_type", new StringValue(event.entity().entityType())),
                        Map.entry("entity_id", new StringValue(event.entity().entityId())),
                        Map.entry("actor_type", new StringValue(actor.actorType().name())),
                        Map.entry("actor_id", new StringValue(actor.actorId().toString())),
                        Map.entry("system_actor_name", systemActor),
                        Map.entry("tenant_id", new StringValue(chain.tenantId().toString())),
                        Map.entry("occurred_at", new InstantValue(occurredAt)),
                        Map.entry(
                                "correlation_id",
                                new StringValue(actor.correlationId().toString())),
                        Map.entry(
                                "retention_class",
                                new StringValue(retention.retentionClass().name())),
                        Map.entry("retention_policy_key", new StringValue(retention.policyKey())),
                        Map.entry(
                                "retention_policy_version",
                                new IntegerValue(retention.policyVersion())),
                        Map.entry("retention_until", retentionUntil),
                        Map.entry("period", new StringValue(chain.epoch().period().toString())),
                        Map.entry("shard_id", new IntegerValue(chain.shardId())),
                        Map.entry("seq", new IntegerValue(sequence)),
                        Map.entry("hash_algo_version", new IntegerValue(hashAlgorithmVersion)),
                        Map.entry("payload", event.payload())));
    }
}
