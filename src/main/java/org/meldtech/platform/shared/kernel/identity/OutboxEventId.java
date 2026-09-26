package org.meldtech.platform.shared.kernel.identity;

import java.util.UUID;

public final class OutboxEventId extends AbstractUuidIdentifier {

    private OutboxEventId(UUID value) {
        super(value);
    }

    public static OutboxEventId parse(String value) {
        return new OutboxEventId(parseCanonical(value, "OutboxEventId"));
    }

    public static OutboxEventId newId(IdGenerator generator) {
        return new OutboxEventId(generateVersionSeven(generator, "OutboxEventId"));
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate instanceof OutboxEventId other && valueEquals(other);
    }
}
