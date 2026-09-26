package org.meldtech.platform.shared.kernel.error;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

public record ProblemCodeDefinition(
        URI type, String title, int status, String detail, Map<String, ExtensionRule> extensions) {

    public ProblemCodeDefinition {
        Objects.requireNonNull(type, "type");
        title = requireText(title, "title");
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("status must be between 400 and 599");
        }
        detail = requireText(detail, "detail");
        extensions = Map.copyOf(Objects.requireNonNull(extensions, "extensions"));
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public record ExtensionRule(PrimitiveType type, boolean required) {
        public ExtensionRule {
            Objects.requireNonNull(type, "type");
        }
    }

    public enum PrimitiveType {
        STRING,
        INTEGER,
        NUMBER,
        BOOLEAN
    }
}
