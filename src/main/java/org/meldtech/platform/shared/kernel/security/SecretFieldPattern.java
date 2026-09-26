package org.meldtech.platform.shared.kernel.security;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

public final class SecretFieldPattern {

    private static final Set<String> SECRET_SEGMENTS =
            Set.of("pin", "otp", "token", "secret", "password", "key", "authorization");
    private static final Pattern ACRONYM_BOUNDARY = Pattern.compile("([A-Z]+)([A-Z][a-z])");
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Pattern SEPARATOR = Pattern.compile("[._-]+");

    private SecretFieldPattern() {}

    public static boolean isSecretField(String fieldPath) {
        Objects.requireNonNull(fieldPath, "fieldPath");
        String normalized = ACRONYM_BOUNDARY.matcher(fieldPath).replaceAll("$1 $2");
        normalized = CAMEL_BOUNDARY.matcher(normalized).replaceAll("$1 $2");
        normalized = SEPARATOR.matcher(normalized).replaceAll(" ");
        StringTokenizer segments = new StringTokenizer(normalized.toLowerCase(Locale.ROOT));
        while (segments.hasMoreTokens()) {
            String segment = segments.nextToken();
            if (SECRET_SEGMENTS.contains(segment)) {
                return true;
            }
        }
        return false;
    }
}
