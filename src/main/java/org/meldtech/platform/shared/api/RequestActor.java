package org.meldtech.platform.shared.api;

import java.util.Objects;

/** Temporary actor carrier replaced by FEAT-PLAT-003's definitive ActorContext. */
public record RequestActor(RequestActorType type, String id) {

    public RequestActor {
        Objects.requireNonNull(type, "type");
        id = requireText(id, "id");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
