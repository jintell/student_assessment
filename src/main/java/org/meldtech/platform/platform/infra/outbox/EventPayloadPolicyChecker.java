package org.meldtech.platform.platform.infra.outbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.meldtech.platform.shared.kernel.security.SecretFieldPattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class EventPayloadPolicyChecker {

    private static final Set<String> CLASSIFICATIONS =
            Set.of("identifier", "operational", "personal");
    private static final Set<String> COMPOSITIONS = Set.of("allOf", "anyOf", "oneOf");

    private EventPayloadPolicyChecker() {}

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                    "Usage: EventPayloadPolicyChecker <contract-directory>");
        }
        verify(Path.of(arguments[0]));
    }

    static void verify(Path contractDirectory) {
        ObjectMapper objectMapper = new ObjectMapper();
        try (Stream<Path> files = Files.list(contractDirectory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> verifySchema(path, read(path, objectMapper)));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot inspect event contracts in " + contractDirectory, exception);
        }
    }

    private static void verifySchema(Path schemaPath, JsonNode schema) {
        Set<String> consumers = nonEmptyTextSet(schema.path("x-consumers"));
        require(
                !consumers.isEmpty(),
                schemaPath,
                "$",
                "PAYLOAD_CONSUMERS",
                "x-consumers must name at least one consumer");
        inspectSchema(schemaPath, schema, "$", consumers, false);
    }

    private static void inspectSchema(
            Path schemaPath,
            JsonNode schema,
            String fieldPath,
            Set<String> consumers,
            boolean property) {
        if (property && SecretFieldPattern.isSecretField(fieldPath)) {
            fail(
                    schemaPath,
                    fieldPath,
                    "PAYLOAD_SECRET_FIELD",
                    "credential-shaped property names are forbidden");
        }

        boolean object = "object".equals(text(schema.path("type"))) || schema.has("properties");
        boolean array = "array".equals(text(schema.path("type"))) || schema.has("items");
        boolean reference = schema.has("$ref");
        if (object) {
            require(
                    schema.has("additionalProperties")
                            && !schema.path("additionalProperties").asBoolean(true),
                    schemaPath,
                    fieldPath,
                    "PAYLOAD_CLOSED_OBJECT",
                    "object schemas must set additionalProperties to false");
            for (Map.Entry<String, JsonNode> child : schema.path("properties").properties()) {
                inspectSchema(
                        schemaPath,
                        child.getValue(),
                        childPath(fieldPath, child.getKey()),
                        consumers,
                        true);
            }
        } else if (array) {
            require(
                    schema.has("maxItems"),
                    schemaPath,
                    fieldPath,
                    "PAYLOAD_UNBOUNDED",
                    "array properties must declare maxItems");
            inspectSchema(schemaPath, schema.path("items"), fieldPath + "[]", consumers, false);
        } else if (property && !reference && !hasComposition(schema)) {
            inspectLeaf(schemaPath, schema, fieldPath, consumers);
        }

        for (Map.Entry<String, JsonNode> definition : schema.path("$defs").properties()) {
            inspectSchema(
                    schemaPath,
                    definition.getValue(),
                    fieldPath + ".$defs." + definition.getKey(),
                    consumers,
                    false);
        }
        for (String composition : COMPOSITIONS) {
            int index = 0;
            for (JsonNode alternative : schema.path(composition).values()) {
                inspectSchema(
                        schemaPath,
                        alternative,
                        fieldPath + "." + composition + "[" + index + "]",
                        consumers,
                        property);
                index++;
            }
        }
    }

    private static void inspectLeaf(
            Path schemaPath, JsonNode schema, String fieldPath, Set<String> consumers) {
        Set<String> requiredBy = nonEmptyTextSet(schema.path("x-required-by"));
        require(
                !requiredBy.isEmpty() && consumers.containsAll(requiredBy),
                schemaPath,
                fieldPath,
                "PAYLOAD_FIELD_OWNER",
                "x-required-by must be a non-empty subset of x-consumers");

        String classification = text(schema.path("x-data-classification"));
        require(
                CLASSIFICATIONS.contains(classification),
                schemaPath,
                fieldPath,
                "PAYLOAD_CLASSIFICATION",
                "x-data-classification must be identifier, operational, or personal");
        if ("personal".equals(classification)) {
            require(
                    !text(schema.path("x-identifier-insufficient-reason")).isBlank(),
                    schemaPath,
                    fieldPath,
                    "PAYLOAD_PERSONAL_JUSTIFICATION",
                    "personal fields require x-identifier-insufficient-reason");
        }
        if ("personal".equals(classification) || "identifier".equals(classification)) {
            require(
                    !schema.has("default") && !schema.has("examples") && !schema.has("const"),
                    schemaPath,
                    fieldPath,
                    "PAYLOAD_SENSITIVE_EXAMPLE",
                    "personal and identifier fields cannot carry example or constant values");
        }
        if ("string".equals(text(schema.path("type")))) {
            require(
                    schema.has("maxLength") || schema.has("const") || schema.has("enum"),
                    schemaPath,
                    fieldPath,
                    "PAYLOAD_UNBOUNDED",
                    "string properties must declare maxLength");
        }
    }

    private static boolean hasComposition(JsonNode schema) {
        return COMPOSITIONS.stream().anyMatch(schema::has);
    }

    private static String childPath(String parent, String child) {
        return "$".equals(parent) ? child : parent + "." + child;
    }

    private static Set<String> nonEmptyTextSet(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            return Set.of();
        }
        Set<String> values = new HashSet<>();
        for (JsonNode value : node.values()) {
            String text = text(value);
            if (text.isBlank()) {
                return Set.of();
            }
            values.add(text);
        }
        return Set.copyOf(values);
    }

    private static String text(JsonNode node) {
        return node.isString() ? node.stringValue() : "";
    }

    private static JsonNode read(Path path, ObjectMapper objectMapper) {
        try {
            return objectMapper.readTree(Files.readString(path));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read event schema " + path, exception);
        }
    }

    private static void require(
            boolean condition, Path schemaPath, String fieldPath, String rule, String detail) {
        if (!condition) {
            fail(schemaPath, fieldPath, rule, detail);
        }
    }

    private static void fail(Path schemaPath, String fieldPath, String rule, String detail) {
        throw new IllegalStateException(
                "%s %s %s: %s".formatted(rule, schemaPath.getFileName(), fieldPath, detail));
    }
}
