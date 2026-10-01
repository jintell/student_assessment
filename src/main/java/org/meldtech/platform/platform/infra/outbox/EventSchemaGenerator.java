package org.meldtech.platform.platform.infra.outbox;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.meldtech.platform.shared.kernel.outbox.IntegrationEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class EventSchemaGenerator {

    private EventSchemaGenerator() {}

    public static void main(String[] arguments) throws ClassNotFoundException {
        if (arguments.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: EventSchemaGenerator <contract-directory> <event-class>...");
        }
        Path contracts = Path.of(arguments[0]);
        List<Class<?>> eventTypes =
                Arrays.stream(arguments).skip(1).map(EventSchemaGenerator::loadClass).toList();
        verify(contracts, eventTypes);
    }

    static void verify(Path contracts, List<Class<?>> eventClasses) {
        ObjectMapper objectMapper = new ObjectMapper();
        for (Class<?> eventClass : List.copyOf(eventClasses)) {
            verifyEvent(contracts, eventClass, objectMapper);
        }
    }

    private static void verifyEvent(
            Path contracts, Class<?> eventClass, ObjectMapper objectMapper) {
        if (!eventClass.isRecord() || !IntegrationEvent.class.isAssignableFrom(eventClass)) {
            throw new IllegalArgumentException(
                    eventClass.getName() + " must be an IntegrationEvent record");
        }
        RegisteredEventContract registration =
                Objects.requireNonNull(
                        eventClass.getAnnotation(RegisteredEventContract.class),
                        () -> eventClass.getName() + " has no RegisteredEventContract");
        EventType eventType = EventType.parse(registration.value());
        JsonNode baseline = read(contracts.resolve(eventType + ".json"), objectMapper);
        Set<String> generatedRequired = new HashSet<>();
        for (RecordComponent component : eventClass.getRecordComponents()) {
            generatedRequired.add(component.getName());
            JsonNode property = baseline.path("properties").path(component.getName());
            if (property.isMissingNode()) {
                throw drift(eventType, "missing property " + component.getName());
            }
            String expectedType = jsonType(component.getType());
            if (!expectedType.equals(property.path("type").stringValue())) {
                throw drift(eventType, "type changed for " + component.getName());
            }
        }
        Set<String> baselineProperties = new HashSet<>();
        baseline.path("properties").propertyNames().forEach(baselineProperties::add);
        if (!baselineProperties.equals(generatedRequired)) {
            throw drift(eventType, "record and baseline property sets differ");
        }
        Set<String> baselineRequired = new HashSet<>();
        for (JsonNode required : baseline.path("required").values()) {
            baselineRequired.add(required.stringValue());
        }
        if (!baselineRequired.equals(generatedRequired)) {
            throw drift(eventType, "record components must be required in the baseline");
        }
    }

    private static JsonNode read(Path path, ObjectMapper objectMapper) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Missing registered event schema " + path);
        }
        try {
            return objectMapper.readTree(Files.readString(path));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot read registered event schema " + path, exception);
        }
    }

    private static String jsonType(Class<?> type) {
        if (type == String.class || type.isEnum()) {
            return "string";
        }
        if (type == byte.class
                || type == short.class
                || type == int.class
                || type == long.class
                || type == Byte.class
                || type == Short.class
                || type == Integer.class
                || type == Long.class) {
            return "integer";
        }
        if (type == float.class
                || type == double.class
                || type == Float.class
                || type == Double.class) {
            return "number";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type.isArray() || List.class.isAssignableFrom(type)) {
            return "array";
        }
        if (type.isRecord()) {
            return "object";
        }
        throw new IllegalArgumentException("Unsupported event component type " + type.getName());
    }

    private static IllegalStateException drift(EventType eventType, String detail) {
        return new IllegalStateException("EVENT_SCHEMA_DRIFT " + eventType + ": " + detail);
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            throw new IllegalArgumentException("Event class not found: " + className, exception);
        }
    }
}
