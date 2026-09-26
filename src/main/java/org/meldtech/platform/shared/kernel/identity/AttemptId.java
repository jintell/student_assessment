package org.meldtech.platform.shared.kernel.identity;

import java.util.UUID;

public final class AttemptId extends AbstractUuidIdentifier {

    private AttemptId(UUID value) {
        super(value);
    }

    public static AttemptId parse(String value) {
        return new AttemptId(parseCanonical(value, "AttemptId"));
    }

    public static AttemptId newId(IdGenerator generator) {
        return new AttemptId(generateVersionSeven(generator, "AttemptId"));
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate instanceof AttemptId other && valueEquals(other);
    }
}
