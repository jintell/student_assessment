package org.meldtech.platform.shared.kernel.identity;

import java.util.Objects;
import java.util.UUID;

abstract class AbstractUuidIdentifier {

    private final UUID value;

    AbstractUuidIdentifier(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    static UUID parseCanonical(String value, String typeName) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(typeName + " must be a canonical UUID");
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException(typeName + " must be a canonical UUID");
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(typeName + " must be a canonical UUID", exception);
        }
    }

    static UUID generateVersionSeven(IdGenerator generator, String typeName) {
        UUID generated = Objects.requireNonNull(generator, "generator").generate();
        if (generated == null || generated.version() != 7) {
            throw new IllegalArgumentException(typeName + " generator must produce UUIDv7 values");
        }
        return generated;
    }

    @Override
    public final String toString() {
        return value.toString();
    }

    final boolean valueEquals(AbstractUuidIdentifier candidate) {
        return value.equals(candidate.value);
    }

    @Override
    public final int hashCode() {
        return value.hashCode();
    }
}
