package org.meldtech.platform.platform.infra.outbox;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class EventSchemaCompatibilityChecker {

    private static final Set<String> LOWER_BOUND_KEYS =
            Set.of("minimum", "exclusiveMinimum", "minLength", "minItems");
    private static final Set<String> UPPER_BOUND_KEYS =
            Set.of("maximum", "exclusiveMaximum", "maxLength", "maxItems");
    private static final Set<String> SEMANTIC_KEYS =
            Set.of("x-semantic-id", "description", "x-units", "x-meaning");

    private EventSchemaCompatibilityChecker() {}

    public static void main(String[] arguments) {
        if (arguments.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: EventSchemaCompatibilityChecker "
                            + "<baseline> <current> <consumers.yaml> <retirements>");
        }
        verify(
                Path.of(arguments[0]),
                Path.of(arguments[1]),
                Path.of(arguments[2]),
                Path.of(arguments[3]));
    }

    static void verify(
            Path baselineDirectory,
            Path currentDirectory,
            Path consumersFile,
            Path retirementsDirectory) {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, JsonNode> baseline = schemas(baselineDirectory, mapper);
        Map<String, JsonNode> current = schemas(currentDirectory, mapper);
        ConsumerCoverage coverage = ConsumerCoverage.load(consumersFile);

        for (Map.Entry<String, JsonNode> entry : baseline.entrySet()) {
            JsonNode candidate = current.get(entry.getKey());
            if (candidate == null) {
                verifyRetirement(entry.getKey(), retirementsDirectory);
            } else {
                verifyIdentity(entry.getKey(), candidate);
                compare(entry.getKey(), entry.getValue(), candidate, coverage);
            }
        }
        for (Map.Entry<String, JsonNode> entry : current.entrySet()) {
            if (!baseline.containsKey(entry.getKey())) {
                verifyIdentity(entry.getKey(), entry.getValue());
            }
        }
    }

    private static Map<String, JsonNode> schemas(Path directory, ObjectMapper mapper) {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("Missing event schema directory " + directory);
        }
        Map<String, JsonNode> schemas = new HashMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(
                            path -> {
                                String eventType =
                                        path.getFileName().toString().replaceFirst("\\.json$", "");
                                try {
                                    schemas.put(eventType, mapper.readTree(Files.readString(path)));
                                } catch (IOException exception) {
                                    throw new IllegalStateException(
                                            "Cannot read event schema " + path, exception);
                                }
                            });
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot list event schemas " + directory, exception);
        }
        return Map.copyOf(schemas);
    }

    private static void verifyIdentity(String eventType, JsonNode schema) {
        EventType.parse(eventType);
        if (!schema.path("$id").stringValue().endsWith(eventType)
                || !eventType.equals(schema.at("/properties/eventType/const").stringValue())) {
            throw incompatible(eventType, "/", "COMPAT_IDENTITY_MISMATCH");
        }
        if (schema.path("x-owner").stringValue().isBlank()
                || !schema.path("x-consumers").isArray()
                || schema.path("x-consumers").isEmpty()) {
            throw incompatible(eventType, "/", "COMPAT_REGISTRATION_INCOMPLETE");
        }
    }

    private static void compare(
            String eventType, JsonNode baseline, JsonNode candidate, ConsumerCoverage coverage) {
        Set<String> oldRequired = textSet(baseline.path("required"));
        Set<String> newRequired = textSet(candidate.path("required"));
        JsonNode oldProperties = baseline.path("properties");
        JsonNode newProperties = candidate.path("properties");
        for (String propertyName : oldProperties.propertyNames()) {
            JsonNode oldProperty = oldProperties.path(propertyName);
            JsonNode newProperty = newProperties.path(propertyName);
            String pointer = "/properties/" + propertyName;
            if (newProperty.isMissingNode()) {
                throw incompatible(eventType, pointer, "COMPAT_FIELD_REMOVED");
            }
            compareProperty(eventType, pointer, oldProperty, newProperty, coverage);
            if (!oldRequired.contains(propertyName) && newRequired.contains(propertyName)) {
                throw incompatible(eventType, pointer, "COMPAT_OPTIONAL_BECAME_REQUIRED");
            }
        }
        for (String propertyName : newProperties.propertyNames()) {
            if (!oldProperties.has(propertyName)) {
                if (newRequired.contains(propertyName)) {
                    throw incompatible(
                            eventType,
                            "/properties/" + propertyName,
                            "COMPAT_REQUIRED_FIELD_ADDED");
                }
                String semanticId =
                        newProperties.path(propertyName).path("x-semantic-id").stringValue();
                if (semanticId == null || semanticId.isBlank()) {
                    throw incompatible(
                            eventType,
                            "/properties/" + propertyName,
                            "COMPAT_NEW_FIELD_SEMANTIC_ID_MISSING");
                }
            }
        }
    }

    private static void compareProperty(
            String eventType,
            String pointer,
            JsonNode baseline,
            JsonNode candidate,
            ConsumerCoverage coverage) {
        if (!types(candidate.path("type")).containsAll(types(baseline.path("type")))) {
            throw incompatible(eventType, pointer + "/type", "COMPAT_TYPE_NARROWED");
        }
        for (String key : LOWER_BOUND_KEYS) {
            if (candidate.has(key)
                    && (!baseline.has(key)
                            || decimal(candidate.path(key)).compareTo(decimal(baseline.path(key)))
                                    > 0)) {
                throw incompatible(eventType, pointer + "/" + key, "COMPAT_BOUND_TIGHTENED");
            }
        }
        for (String key : UPPER_BOUND_KEYS) {
            if (candidate.has(key)
                    && (!baseline.has(key)
                            || decimal(candidate.path(key)).compareTo(decimal(baseline.path(key)))
                                    < 0)) {
                throw incompatible(eventType, pointer + "/" + key, "COMPAT_BOUND_TIGHTENED");
            }
        }
        if (!Objects.equals(text(baseline, "format"), text(candidate, "format"))) {
            throw incompatible(eventType, pointer + "/format", "COMPAT_FORMAT_CHANGED");
        }
        for (String key : SEMANTIC_KEYS) {
            if (!Objects.equals(text(baseline, key), text(candidate, key))) {
                throw incompatible(eventType, pointer + "/" + key, "COMPAT_SEMANTICS_CHANGED");
            }
        }
        Set<String> oldEnum = textSet(baseline.path("enum"));
        Set<String> newEnum = textSet(candidate.path("enum"));
        if (!newEnum.containsAll(oldEnum)) {
            throw incompatible(eventType, pointer + "/enum", "COMPAT_ENUM_VALUE_REMOVED");
        }
        if (!oldEnum.isEmpty()
                && newEnum.size() > oldEnum.size()
                && !coverage.covers(eventType, textSet(candidate.path("x-required-by")))) {
            throw incompatible(eventType, pointer + "/enum", "COMPAT_ENUM_DEFAULT_MISSING");
        }
    }

    private static void verifyRetirement(String eventType, Path directory) {
        Path evidence = directory.resolve(eventType + ".yaml");
        Object root = loadYaml(evidence);
        if (!(root instanceof Map<?, ?> values)
                || number(values.get("consumptionCount")) != 0
                || !Boolean.TRUE.equals(values.get("ownerApproved"))) {
            throw incompatible(eventType, "/", "COMPAT_RETIREMENT_EVIDENCE_MISSING");
        }
        LocalDate start = date(values.get("zeroConsumptionStart"));
        LocalDate end = date(values.get("zeroConsumptionEnd"));
        if (ChronoUnit.DAYS.between(start, end) < 30) {
            throw incompatible(eventType, "/", "COMPAT_RETIREMENT_WINDOW_TOO_SHORT");
        }
    }

    private static Object loadYaml(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Missing compatibility evidence " + path);
        }
        try (InputStream input = Files.newInputStream(path)) {
            return new Yaml().load(input);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot read compatibility evidence " + path, exception);
        }
    }

    private static int number(@Nullable Object value) {
        return value instanceof Number number ? number.intValue() : Integer.MIN_VALUE;
    }

    private static LocalDate date(@Nullable Object value) {
        if (value instanceof java.util.Date parsedDate) {
            return parsedDate.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private static Set<String> types(JsonNode node) {
        return node.isArray() ? textSet(node) : Set.of(node.stringValue());
    }

    private static Set<String> textSet(JsonNode array) {
        if (!array.isArray()) {
            return Set.of();
        }
        Set<String> values = new HashSet<>();
        for (JsonNode value : array.values()) {
            values.add(value.stringValue());
        }
        return Set.copyOf(values);
    }

    private static @Nullable String text(JsonNode node, String key) {
        return node.has(key) ? node.path(key).toString() : null;
    }

    private static BigDecimal decimal(JsonNode node) {
        return node.decimalValue();
    }

    private static IllegalStateException incompatible(
            String eventType, String pointer, String assertion) {
        return new IllegalStateException(assertion + " event=" + eventType + " pointer=" + pointer);
    }

    private record ConsumerCoverage(Map<String, Set<String>> consumersWithDefaults) {

        private static ConsumerCoverage load(Path path) {
            Object root = loadYaml(path);
            Map<String, Set<String>> coverage = new HashMap<>();
            if (root instanceof Map<?, ?> rootMap
                    && rootMap.get("versions") instanceof Iterable<?> versions) {
                for (Object version : versions) {
                    if (!(version instanceof Map<?, ?> versionMap)) {
                        continue;
                    }
                    String eventType = String.valueOf(versionMap.get("eventType"));
                    Set<String> covered = new HashSet<>();
                    if (versionMap.get("consumers") instanceof Map<?, ?> consumers) {
                        for (Map.Entry<?, ?> consumer : consumers.entrySet()) {
                            if (consumer.getValue() instanceof Map<?, ?> settings
                                    && nonBlank(settings.get("enumDefault"))
                                    && nonBlank(settings.get("provingTest"))) {
                                covered.add(String.valueOf(consumer.getKey()));
                            }
                        }
                    }
                    coverage.put(eventType, Set.copyOf(covered));
                }
            }
            return new ConsumerCoverage(Map.copyOf(coverage));
        }

        private boolean covers(String eventType, Set<String> requiredConsumers) {
            return consumersWithDefaults
                    .getOrDefault(eventType, Set.of())
                    .containsAll(requiredConsumers);
        }

        private static boolean nonBlank(@Nullable Object value) {
            return value instanceof String text && !text.isBlank();
        }
    }
}
