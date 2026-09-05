package org.meldtech.platform.shared.api;

import java.util.Objects;
import java.util.UUID;

/** Temporary tenant identifier replaced by FEAT-PLAT-003's definitive TenantId value object. */
public record RequestTenantId(UUID value) {

    public RequestTenantId {
        Objects.requireNonNull(value, "value");
    }
}
