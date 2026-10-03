package org.meldtech.platform.platform.infra.observability;

import java.nio.file.Path;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class ObservabilityContractFiles {

    private ObservabilityContractFiles() {}

    static JsonNode readObject(Path path) {
        JsonNode document;
        try {
            document = new ObjectMapper().readTree(path.toFile());
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Cannot read observability contract " + path.getFileName(), exception);
        }
        if (!document.isObject()) {
            throw invalid(path, "root must be an object");
        }
        return document;
    }

    static JsonNode required(JsonNode parent, String field, Path source) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            throw invalid(source, "missing field " + field);
        }
        return value;
    }

    static String requiredText(JsonNode parent, String field, Path source) {
        JsonNode value = required(parent, field, source);
        if (!value.isString()
                || Objects.requireNonNull(value.stringValue(), "string value").isBlank()) {
            throw invalid(source, "field " + field + " must be non-blank text");
        }
        return Objects.requireNonNull(value.stringValue(), "string value");
    }

    static int requiredNonNegativeInt(JsonNode parent, String field, Path source) {
        JsonNode value = required(parent, field, source);
        if (!value.isInt() || value.asInt() < 0) {
            throw invalid(source, "field " + field + " must be a non-negative integer");
        }
        return value.asInt();
    }

    static IllegalStateException invalid(Path source, String reason) {
        Objects.requireNonNull(source, "source");
        return new IllegalStateException(
                "OBSERVABILITY_CONTRACT_INVALID " + source.getFileName() + " " + reason);
    }
}
