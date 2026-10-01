package org.meldtech.platform.platform.infra.outbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.meldtech.platform.shared.kernel.security.SecretFieldPattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class EventPayloadLeakScanner {

    private EventPayloadLeakScanner() {}

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                    "Usage: EventPayloadLeakScanner <payload-capture-directory>");
        }
        scan(Path.of(arguments[0]));
    }

    static void scan(Path captureDirectory) {
        if (!Files.isDirectory(captureDirectory)) {
            throw new IllegalStateException("EVENT_PAYLOAD_CAPTURE_MISSING");
        }
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger payloadCount = new AtomicInteger();
        try (Stream<Path> files = Files.walk(captureDirectory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(
                            path -> {
                                payloadCount.incrementAndGet();
                                inspect(read(path, objectMapper), "$", path);
                            });
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot scan captured event payloads", exception);
        }
        if (payloadCount.get() == 0) {
            throw new IllegalStateException("EVENT_PAYLOAD_CAPTURE_EMPTY");
        }
    }

    private static void inspect(JsonNode node, String fieldPath, Path source) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                String childPath =
                        "$".equals(fieldPath)
                                ? property.getKey()
                                : fieldPath + "." + property.getKey();
                if (SecretFieldPattern.isSecretField(childPath)) {
                    throw new IllegalStateException(
                            "EVENT_PAYLOAD_SECRET_FIELD " + source.getFileName() + " " + childPath);
                }
                inspect(property.getValue(), childPath, source);
            }
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode element : node.values()) {
                inspect(element, fieldPath + "[" + index + "]", source);
                index++;
            }
        }
    }

    private static JsonNode read(Path path, ObjectMapper objectMapper) {
        try {
            return objectMapper.readTree(Files.readString(path));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot read captured event payload " + path, exception);
        }
    }
}
