package org.meldtech.platform.platform.infra.outbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.meldtech.platform.shared.kernel.outbox.IntegrationEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class RegisteredEventSchemaValidator {

    private final Path contractDirectory;
    private final ObjectMapper objectMapper;

    RegisteredEventSchemaValidator(Path contractDirectory, ObjectMapper objectMapper) {
        this.contractDirectory = Objects.requireNonNull(contractDirectory, "contractDirectory");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    String validateAndSerialize(EventType eventType, IntegrationEvent event) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(event, "event");
        JsonNode schema = readSchema(eventType);
        JsonNode payload = objectMapper.valueToTree(event);
        validateObject(eventType, schema, payload);
        return objectMapper.writeValueAsString(payload);
    }

    private JsonNode readSchema(EventType eventType) {
        Path schemaPath = contractDirectory.resolve(eventType + ".json");
        if (!Files.isRegularFile(schemaPath)) {
            throw new IllegalArgumentException("Unregistered event type: " + eventType);
        }
        try {
            return objectMapper.readTree(Files.readString(schemaPath));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read event schema " + schemaPath, exception);
        }
    }

    private static void validateObject(EventType eventType, JsonNode schema, JsonNode payload) {
        if (!payload.isObject()) {
            throw invalid(eventType, "payload must be an object");
        }
        Set<String> required = new HashSet<>();
        for (JsonNode requiredProperty : schema.path("required").values()) {
            required.add(requiredProperty.stringValue());
        }
        for (String name : required) {
            if (!payload.has(name)) {
                throw invalid(eventType, "missing required property " + name);
            }
        }

        JsonNode properties = schema.path("properties");
        Iterator<Map.Entry<String, JsonNode>> payloadProperties = payload.properties().iterator();
        while (payloadProperties.hasNext()) {
            Map.Entry<String, JsonNode> property = payloadProperties.next();
            JsonNode propertySchema = properties.path(property.getKey());
            if (propertySchema.isMissingNode()) {
                if (!schema.path("additionalProperties").asBoolean(true)) {
                    throw invalid(eventType, "unknown property " + property.getKey());
                }
                continue;
            }
            validateProperty(eventType, property.getKey(), propertySchema, property.getValue());
        }
    }

    private static void validateProperty(
            EventType eventType, String name, JsonNode schema, JsonNode value) {
        String expectedType = schema.path("type").stringValue();
        boolean validType =
                switch (expectedType) {
                    case "string" -> value.isString();
                    case "integer" -> value.isIntegralNumber();
                    case "number" -> value.isNumber();
                    case "boolean" -> value.isBoolean();
                    case "object" -> value.isObject();
                    case "array" -> value.isArray();
                    default -> false;
                };
        if (!validType) {
            throw invalid(eventType, name + " must be " + expectedType);
        }
        JsonNode constant = schema.path("const");
        if (!constant.isMissingNode() && !constant.equals(value)) {
            throw invalid(eventType, name + " does not match its registered constant");
        }
        if (value.isString()) {
            String text = value.stringValue();
            if (schema.has("maxLength") && text.length() > schema.path("maxLength").intValue()) {
                throw invalid(eventType, name + " exceeds maxLength");
            }
            if (schema.has("format") && "uuid".equals(schema.path("format").stringValue())) {
                try {
                    UUID.fromString(text);
                } catch (IllegalArgumentException exception) {
                    throw invalid(eventType, name + " must be a UUID");
                }
            }
        }
        if (value.isIntegralNumber()) {
            long number = value.longValue();
            if (schema.has("minimum") && number < schema.path("minimum").longValue()) {
                throw invalid(eventType, name + " is below minimum");
            }
            if (schema.has("maximum") && number > schema.path("maximum").longValue()) {
                throw invalid(eventType, name + " is above maximum");
            }
        }
    }

    private static IllegalArgumentException invalid(EventType eventType, String reason) {
        return new IllegalArgumentException("Invalid payload for " + eventType + ": " + reason);
    }
}
