package org.meldtech.platform.shared.api;

/** Closed set of platform-scoped operations and the actor identity each permits. */
public enum PlatformOperation {
    PLATFORM_ADMINISTRATION(RequestActorType.WORKFORCE_USER, "platform-administrator"),
    RETENTION_SWEEP(RequestActorType.SYSTEM, "RETENTION_ENGINE"),
    RECONCILIATION(RequestActorType.SYSTEM, "IDP_RECONCILER");

    private final RequestActorType actorType;
    private final String actorId;

    PlatformOperation(RequestActorType actorType, String actorId) {
        this.actorType = actorType;
        this.actorId = actorId;
    }

    public boolean permits(RequestActor actor) {
        return actor.type() == actorType && actor.id().equals(actorId);
    }

    public String settingValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
