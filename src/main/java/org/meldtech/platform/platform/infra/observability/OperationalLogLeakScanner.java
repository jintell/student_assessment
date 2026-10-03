package org.meldtech.platform.platform.infra.observability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.meldtech.platform.shared.kernel.security.SecretFieldPattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class OperationalLogLeakScanner {

    private OperationalLogLeakScanner() {}

    public static void main(String[] arguments) {
        if (arguments.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: OperationalLogLeakScanner <capture-directory> <marker-file>");
        }
        scan(Path.of(arguments[0]), Path.of(arguments[1]));
    }

    static void scan(Path captureDirectory, Path markerFile) {
        if (!Files.isDirectory(captureDirectory)) {
            throw new IllegalStateException("OPERATIONAL_LOG_CAPTURE_MISSING");
        }
        Set<String> markers = readMarkers(markerFile);
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger eventCount = new AtomicInteger();
        try (Stream<Path> files = Files.walk(captureDirectory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted()
                    .forEach(path -> scanFile(path, markers, objectMapper, eventCount));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot scan operational log captures", exception);
        }
        if (eventCount.get() == 0) {
            throw new IllegalStateException("OPERATIONAL_LOG_CAPTURE_EMPTY");
        }
    }

    private static Set<String> readMarkers(Path markerFile) {
        if (!Files.isRegularFile(markerFile)) {
            throw new IllegalStateException("OPERATIONAL_LOG_MARKERS_MISSING");
        }
        try (Stream<String> lines = Files.lines(markerFile)) {
            Set<String> markers =
                    lines.map(String::trim)
                            .filter(line -> !line.isEmpty())
                            .filter(line -> !line.startsWith("#"))
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (markers.isEmpty()) {
                throw new IllegalStateException("OPERATIONAL_LOG_MARKERS_EMPTY");
            }
            return Set.copyOf(markers);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read operational log markers", exception);
        }
    }

    private static void scanFile(
            Path path, Set<String> markers, ObjectMapper objectMapper, AtomicInteger eventCount) {
        try (Stream<String> lines = Files.lines(path)) {
            int[] lineNumber = {0};
            lines.forEach(
                    line -> {
                        lineNumber[0]++;
                        if (line.isBlank()) {
                            return;
                        }
                        markers.forEach(
                                marker -> {
                                    if (line.contains(marker)) {
                                        throw leak("VALUE", path, lineNumber[0], "$");
                                    }
                                });
                        inspect(
                                parse(line, path, lineNumber[0], objectMapper),
                                "$",
                                path,
                                lineNumber[0]);
                        eventCount.incrementAndGet();
                    });
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot read operational log capture " + path.getFileName(), exception);
        }
    }

    private static JsonNode parse(
            String line, Path path, int lineNumber, ObjectMapper objectMapper) {
        JsonNode event;
        try {
            event = objectMapper.readTree(line);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "OPERATIONAL_LOG_INVALID_JSON " + path.getFileName() + ":" + lineNumber,
                    exception);
        }
        if (!event.isObject()) {
            throw new IllegalStateException(
                    "OPERATIONAL_LOG_EVENT_NOT_OBJECT " + path.getFileName() + ":" + lineNumber);
        }
        return event;
    }

    private static void inspect(JsonNode node, String fieldPath, Path path, int lineNumber) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                String childPath =
                        "$".equals(fieldPath)
                                ? property.getKey()
                                : fieldPath + "." + property.getKey();
                if (SecretFieldPattern.isSecretField(childPath)) {
                    throw leak("FIELD", path, lineNumber, childPath);
                }
                inspect(property.getValue(), childPath, path, lineNumber);
            }
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode element : node.values()) {
                inspect(element, fieldPath + "[" + index + "]", path, lineNumber);
                index++;
            }
        }
    }

    private static IllegalStateException leak(
            String kind, Path path, int lineNumber, String fieldPath) {
        return new IllegalStateException(
                "OPERATIONAL_LOG_SECRET_"
                        + kind
                        + " "
                        + path.getFileName()
                        + ":"
                        + lineNumber
                        + " "
                        + fieldPath);
    }
}
