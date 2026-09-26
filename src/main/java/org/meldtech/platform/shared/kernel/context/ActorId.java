package org.meldtech.platform.shared.kernel.context;

import java.util.Objects;

public record ActorId(String value) {

    public ActorId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || !value.equals(value.trim()) || containsControlCharacter(value)) {
            throw new IllegalArgumentException("ActorId must be non-blank opaque text");
        }
        if (value.equalsIgnoreCase("system")) {
            throw new IllegalArgumentException(
                    "System actors must use the closed SystemActor enumeration");
        }
    }

    private static boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    @Override
    public String toString() {
        return value;
    }
}
