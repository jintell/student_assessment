package org.meldtech.platform.shared.kernel.context;

import java.util.Objects;
import java.util.regex.Pattern;

public record SourceIp(String value) {

    private static final Pattern ADDRESS = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    public SourceIp {
        Objects.requireNonNull(value, "value");
        if (!ADDRESS.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "SourceIp must be a valid IPv4 or IPv6 address representation");
        }
    }

    public static SourceIp parse(String value) {
        return new SourceIp(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
