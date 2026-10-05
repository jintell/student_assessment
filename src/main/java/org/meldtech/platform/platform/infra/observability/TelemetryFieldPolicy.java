package org.meldtech.platform.platform.infra.observability;

import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.meldtech.platform.shared.kernel.security.SecretFieldPattern;

final class TelemetryFieldPolicy {

    private static final Pattern ACRONYM_BOUNDARY = Pattern.compile("([A-Z]+)([A-Z][a-z])");
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Pattern SEPARATOR = Pattern.compile("[._-]+");
    private static final Set<String> PERSONAL_DATA_SEGMENTS = Set.of("email", "answer");
    private static final Set<String> PERSONAL_NAME_QUALIFIERS =
            Set.of("candidate", "display", "first", "last", "full");

    private TelemetryFieldPolicy() {}

    static boolean isForbidden(String fieldName) {
        Objects.requireNonNull(fieldName, "fieldName");
        return SecretFieldPattern.isSecretField(fieldName) || containsPersonalData(fieldName);
    }

    private static boolean containsPersonalData(String fieldName) {
        String separated = ACRONYM_BOUNDARY.matcher(fieldName).replaceAll("$1 $2");
        separated = CAMEL_BOUNDARY.matcher(separated).replaceAll("$1 $2");
        separated = SEPARATOR.matcher(separated).replaceAll(" ");
        Set<String> segments = Set.of(separated.toLowerCase(Locale.ROOT).trim().split("\\s+"));
        if (!Collections.disjoint(segments, PERSONAL_DATA_SEGMENTS)) {
            return true;
        }
        return segments.contains("name")
                && !Collections.disjoint(segments, PERSONAL_NAME_QUALIFIERS);
    }
}
