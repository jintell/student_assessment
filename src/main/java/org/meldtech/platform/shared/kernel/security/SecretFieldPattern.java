package org.meldtech.platform.shared.kernel.security;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

public final class SecretFieldPattern {

    private static final Set<String> SECRET_SEGMENTS =
            Set.of("pin", "otp", "token", "secret", "password", "key", "authorization");
    private static final Set<String> PERMITTED_KEY_FIELDS = Set.of("policy_key");
    private static final Pattern ACRONYM_BOUNDARY = Pattern.compile("([A-Z]+)([A-Z][a-z])");
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Pattern SEPARATOR = Pattern.compile("[._-]+");

    private SecretFieldPattern() {}

    public static Set<String> permittedKeyFields() {
        return PERMITTED_KEY_FIELDS;
    }

    public static boolean isSecretField(String fieldPath) {
        Objects.requireNonNull(fieldPath, "fieldPath");
        StringTokenizer segments = normalizedSegments(fieldPath);
        boolean permittedKeyField = PERMITTED_KEY_FIELDS.contains(canonicalLeafName(fieldPath));
        while (segments.hasMoreTokens()) {
            String segment = segments.nextToken();
            if (SECRET_SEGMENTS.contains(segment)) {
                if ("key".equals(segment) && !segments.hasMoreTokens() && permittedKeyField) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private static String canonicalLeafName(String fieldPath) {
        int leafStart = fieldPath.lastIndexOf('.') + 1;
        StringTokenizer segments = normalizedSegments(fieldPath.substring(leafStart));
        StringJoiner canonicalName = new StringJoiner("_");
        while (segments.hasMoreTokens()) {
            canonicalName.add(segments.nextToken());
        }
        return canonicalName.toString();
    }

    private static StringTokenizer normalizedSegments(String value) {
        String normalized = ACRONYM_BOUNDARY.matcher(value).replaceAll("$1 $2");
        normalized = CAMEL_BOUNDARY.matcher(normalized).replaceAll("$1 $2");
        normalized = SEPARATOR.matcher(normalized).replaceAll(" ");
        return new StringTokenizer(normalized.toLowerCase(Locale.ROOT));
    }
}
