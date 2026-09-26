package org.meldtech.platform.shared.kernel.identity;

import java.util.UUID;

public final class TenantId extends AbstractUuidIdentifier {

    private TenantId(UUID value) {
        super(value);
    }

    public static TenantId parse(String value) {
        return new TenantId(parseCanonical(value, "TenantId"));
    }

    public static TenantId newId(IdGenerator generator) {
        return new TenantId(generateVersionSeven(generator, "TenantId"));
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate instanceof TenantId other && valueEquals(other);
    }
}
