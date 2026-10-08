package org.meldtech.platform.audit.application;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.meldtech.platform.shared.kernel.audit.AuditEmitter;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.IntegerValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;
import org.meldtech.platform.shared.kernel.audit.EntityRef;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;

public final class DefaultTenantAuditShardProvisioning implements TenantAuditShardProvisioning {

    public static final String POLICY_CHANGE_EVENT = "audit.AUDIT_SHARD_POLICY_CHANGED.v1";
    private final AuditShardPolicyRepository repository;
    private final AuditEmitter emitter;

    public DefaultTenantAuditShardProvisioning(
            AuditShardPolicyRepository repository, AuditEmitter emitter) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
    }

    @Override
    public Mono<AuditShardPolicy> provisionDefault(
            TenantId tenantId, YearMonth firstEpoch, Instant recordedAt) {
        AuditShardPolicy policy =
                new AuditShardPolicy(tenantId, firstEpoch, AuditShardPolicy.DEFAULT_SHARD_COUNT, 1);
        return repository.provision(policy, recordedAt).thenReturn(policy);
    }

    @Override
    public Mono<AuditShardPolicy> changeAtEpochBoundary(
            TenantId tenantId,
            int newShardCount,
            YearMonth effectivePeriod,
            long policyVersion,
            ActorContext actor,
            Instant recordedAt) {
        return repository
                .current(tenantId)
                .flatMap(
                        current -> {
                            if (!effectivePeriod.isAfter(current.effectivePeriod())) {
                                return Mono.error(
                                        new IllegalArgumentException(
                                                "shard policy changes require a future epoch boundary"));
                            }
                            AuditShardPolicy changed =
                                    new AuditShardPolicy(
                                            tenantId,
                                            effectivePeriod,
                                            newShardCount,
                                            policyVersion);
                            return repository
                                    .appendAtUnopenedEpochBoundary(changed, recordedAt)
                                    .flatMap(
                                            inserted ->
                                                    inserted
                                                            ? emitChange(
                                                                    current,
                                                                    changed,
                                                                    actor,
                                                                    recordedAt)
                                                            : Mono.error(
                                                                    new IllegalStateException(
                                                                            "target audit epoch is already open")));
                        });
    }

    private Mono<AuditShardPolicy> emitChange(
            AuditShardPolicy previous,
            AuditShardPolicy changed,
            ActorContext actor,
            Instant recordedAt) {
        AuditEvent event =
                new AuditEvent(
                        POLICY_CHANGE_EVENT,
                        new EntityRef("audit.shardpolicy", changed.tenantId().toString()),
                        Set.of(RetentionClass.RESULT_CORRECTION_EVIDENCE),
                        new ObjectValue(
                                Map.of(
                                        "previous_shard_count",
                                                new IntegerValue(previous.shardCount()),
                                        "new_shard_count", new IntegerValue(changed.shardCount()),
                                        "effective_period",
                                                new StringValue(
                                                        changed.effectivePeriod().toString()),
                                        "policy_version",
                                                new IntegerValue(changed.policyVersion()))));
        return Mono.from(emitter.emit(event, actor, recordedAt)).thenReturn(changed);
    }
}
