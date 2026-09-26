package org.meldtech.platform.shared.api;

import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorType;

/** Closed set of platform-scoped operations and the actor identity each permits. */
public enum PlatformOperation {
    PLATFORM_ADMINISTRATION(ActorType.WORKFORCE_USER, "platform-administrator"),
    RETENTION_SWEEP(ActorType.SYSTEM, "RETENTION_ENGINE"),
    RECONCILIATION(ActorType.SYSTEM, "IDP_RECONCILER");

    private final ActorType actorType;
    private final String actorId;

    PlatformOperation(ActorType actorType, String actorId) {
        this.actorType = actorType;
        this.actorId = actorId;
    }

    public boolean permits(ActorContext actor) {
        return actor.actorType() == actorType && actor.actorId().toString().equals(actorId);
    }

    public String settingValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
