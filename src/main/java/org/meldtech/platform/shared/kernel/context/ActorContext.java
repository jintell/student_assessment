package org.meldtech.platform.shared.kernel.context;

import java.util.Objects;
import java.util.Optional;
import org.meldtech.platform.shared.kernel.identity.TenantId;

public record ActorContext(
        ActorType actorType,
        ActorId actorId,
        Optional<TenantId> tenantId,
        CorrelationId correlationId,
        SourceIp sourceIp,
        Optional<SystemActor> systemActorName) {

    public ActorContext {
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(sourceIp, "sourceIp");
        Objects.requireNonNull(systemActorName, "systemActorName");
        if (actorType == ActorType.SYSTEM && systemActorName.isEmpty()) {
            throw new IllegalArgumentException(
                    "A system actor requires an enumerated system actor name");
        }
        if (actorType != ActorType.SYSTEM && systemActorName.isPresent()) {
            throw new IllegalArgumentException("Only system actors may have a system actor name");
        }
        if (actorType == ActorType.CANDIDATE && tenantId.isEmpty()) {
            throw new IllegalArgumentException("A candidate actor requires a tenant");
        }
    }

    public static ActorContext candidate(
            ActorId actorId, TenantId tenantId, CorrelationId correlationId, SourceIp sourceIp) {
        return new ActorContext(
                ActorType.CANDIDATE,
                actorId,
                Optional.of(Objects.requireNonNull(tenantId, "tenantId")),
                correlationId,
                sourceIp,
                Optional.empty());
    }

    public static ActorContext tenantWorkforce(
            ActorId actorId, TenantId tenantId, CorrelationId correlationId, SourceIp sourceIp) {
        return new ActorContext(
                ActorType.WORKFORCE_USER,
                actorId,
                Optional.of(Objects.requireNonNull(tenantId, "tenantId")),
                correlationId,
                sourceIp,
                Optional.empty());
    }

    public static ActorContext platformWorkforce(
            ActorId actorId, CorrelationId correlationId, SourceIp sourceIp) {
        return new ActorContext(
                ActorType.WORKFORCE_USER,
                actorId,
                Optional.empty(),
                correlationId,
                sourceIp,
                Optional.empty());
    }

    public static ActorContext tenantSystem(
            SystemActor actor, TenantId tenantId, CorrelationId correlationId, SourceIp sourceIp) {
        return system(
                actor,
                Optional.of(Objects.requireNonNull(tenantId, "tenantId")),
                correlationId,
                sourceIp);
    }

    public static ActorContext platformSystem(
            SystemActor actor, CorrelationId correlationId, SourceIp sourceIp) {
        return system(actor, Optional.empty(), correlationId, sourceIp);
    }

    private static ActorContext system(
            SystemActor actor,
            Optional<TenantId> tenantId,
            CorrelationId correlationId,
            SourceIp sourceIp) {
        SystemActor systemActor = Objects.requireNonNull(actor, "actor");
        return new ActorContext(
                ActorType.SYSTEM,
                new ActorId(systemActor.name()),
                tenantId,
                correlationId,
                sourceIp,
                Optional.of(systemActor));
    }
}
