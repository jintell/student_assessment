package org.meldtech.platform.shared.kernel.context;

import java.util.Objects;
import java.util.regex.Pattern;

public record ActorId(String value) {

    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    public ActorId {
        Objects.requireNonNull(value, "value");
        if (!SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "ActorId must be an opaque identifier, not an email or display name");
        }
        if (value.equalsIgnoreCase("system")) {
            throw new IllegalArgumentException(
                    "System actors must use the closed SystemActor enumeration");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
