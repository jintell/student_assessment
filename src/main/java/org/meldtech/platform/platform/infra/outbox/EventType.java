package org.meldtech.platform.platform.infra.outbox;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record EventType(String context, String name, int version) {

    private static final Pattern FORMAT =
            Pattern.compile("([a-z][a-z0-9]*)\\.([A-Z][A-Za-z0-9]*)\\.v([1-9][0-9]*)");

    EventType {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(name, "name");
        if (version < 1) {
            throw new IllegalArgumentException("Event version must be positive");
        }
    }

    static EventType parse(String value) {
        Objects.requireNonNull(value, "value");
        Matcher matcher = FORMAT.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Event type must match <context>.<Event>.v<n>: " + value);
        }
        return new EventType(
                matcher.group(1), matcher.group(2), Integer.parseInt(matcher.group(3)));
    }

    @Override
    public String toString() {
        return context + "." + name + ".v" + version;
    }
}
