package org.meldtech.platform.shared.kernel.audit;

import java.util.Objects;
import java.util.regex.Pattern;

public record EntityRef(String entityType, String entityId) {

    private static final Pattern ENTITY_TYPE =
            Pattern.compile("[a-z][a-z0-9]*(?:[.][a-z][a-z0-9]*)*");

    public EntityRef {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityId, "entityId");
        if (!ENTITY_TYPE.matcher(entityType).matches()) {
            throw new IllegalArgumentException(
                    "entityType must be a registered bounded-context type");
        }
        if (entityId.isBlank()) {
            throw new IllegalArgumentException("entityId must not be blank");
        }
    }
}
