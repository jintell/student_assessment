package org.meldtech.platform.shared.kernel.identity;

import java.util.UUID;

public final class ResultId extends AbstractUuidIdentifier {

    private ResultId(UUID value) {
        super(value);
    }

    public static ResultId parse(String value) {
        return new ResultId(parseCanonical(value, "ResultId"));
    }

    public static ResultId newId(IdGenerator generator) {
        return new ResultId(generateVersionSeven(generator, "ResultId"));
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate instanceof ResultId other && valueEquals(other);
    }
}
