package org.meldtech.platform.shared.kernel.idempotency;

import java.util.Objects;

public record IdempotencyKey(String value) {

    public static final int MAX_LENGTH = 128;

    public IdempotencyKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()
                || value.length() > MAX_LENGTH
                || !value.equals(value.trim())
                || !value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e)) {
            throw new IllegalArgumentException(
                    "IdempotencyKey must be 1-128 visible ASCII characters without surrounding whitespace");
        }
    }

    @Override
    public String toString() {
        return "IdempotencyKey[redacted]";
    }
}
