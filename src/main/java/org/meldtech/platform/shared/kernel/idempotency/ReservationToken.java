package org.meldtech.platform.shared.kernel.idempotency;

import java.util.Objects;

public record ReservationToken(String value) {

    public ReservationToken {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException("ReservationToken must not be blank");
        }
    }
}
