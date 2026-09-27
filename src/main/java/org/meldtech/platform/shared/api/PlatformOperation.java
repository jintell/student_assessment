package org.meldtech.platform.shared.api;

import java.util.Optional;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorType;
import org.meldtech.platform.shared.kernel.context.SystemActor;

/** Closed set of platform-scoped operations and the actor identity each permits. */
public enum PlatformOperation {
    PLATFORM_ADMINISTRATION("platform-administrator"),
    RETENTION_SWEEP(SystemActor.RETENTION_ENGINE),
    RECONCILIATION(SystemActor.IDP_RECONCILER);

    private final Optional<String> workforceActorId;
    private final Optional<SystemActor> systemActor;

    PlatformOperation(String workforceActorId) {
        this.workforceActorId = Optional.of(workforceActorId);
        this.systemActor = Optional.empty();
    }

    PlatformOperation(SystemActor systemActor) {
        this.workforceActorId = Optional.empty();
        this.systemActor = Optional.of(systemActor);
    }

    public boolean permits(ActorContext actor) {
        if (systemActor.isPresent()) {
            return actor.systemActorName().equals(systemActor);
        }
        return actor.actorType() == ActorType.WORKFORCE_USER
                && workforceActorId.filter(actor.actorId().toString()::equals).isPresent();
    }

    public String settingValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
