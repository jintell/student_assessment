package org.meldtech.platform.shared.kernel.context;

import java.util.Objects;
import java.util.regex.Pattern;

public record CorrelationId(String value) {

    private static final Pattern STRICT_ULID = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");

    public CorrelationId {
        Objects.requireNonNull(value, "value");
        if (!STRICT_ULID.matcher(value).matches()) {
            throw new IllegalArgumentException("CorrelationId must be a canonical uppercase ULID");
        }
    }

    public static CorrelationId parse(String value) {
        return new CorrelationId(value);
    }

    public static boolean isValid(String value) {
        return value != null && STRICT_ULID.matcher(value).matches();
    }

    @Override
    public String toString() {
        return value;
    }
}
