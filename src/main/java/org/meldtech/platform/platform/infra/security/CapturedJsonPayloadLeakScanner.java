package org.meldtech.platform.platform.infra.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.meldtech.platform.shared.kernel.security.SecretFieldPattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class CapturedJsonPayloadLeakScanner {

    private CapturedJsonPayloadLeakScanner() {}

    public static void scan(Path captureDirectory, String diagnosticPrefix) {
        if (!Files.isDirectory(captureDirectory)) {
            throw new IllegalStateException(diagnosticPrefix + "_CAPTURE_MISSING");
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
                                inspect(read(path, objectMapper), "$", path, diagnosticPrefix);
                            });
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot scan captured "
                            + diagnosticPrefix.toLowerCase(Locale.ROOT)
                            + " payloads",
                    exception);
        }
        if (payloadCount.get() == 0) {
            throw new IllegalStateException(diagnosticPrefix + "_CAPTURE_EMPTY");
        }
    }

    private static void inspect(
            JsonNode node, String fieldPath, Path source, String diagnosticPrefix) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                String childPath =
                        "$".equals(fieldPath)
                                ? property.getKey()
                                : fieldPath + "." + property.getKey();
                if (SecretFieldPattern.isSecretField(childPath)) {
                    throw new IllegalStateException(
                            diagnosticPrefix
                                    + "_SECRET_FIELD "
                                    + source.getFileName()
                                    + " "
                                    + childPath);
                }
                inspect(property.getValue(), childPath, source, diagnosticPrefix);
            }
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode element : node.values()) {
                inspect(element, fieldPath + "[" + index + "]", source, diagnosticPrefix);
                index++;
            }
        }
    }

    private static JsonNode read(Path path, ObjectMapper objectMapper) {
        try {
            return objectMapper.readTree(Files.readString(path));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot read captured payload " + path.getFileName(), exception);
        }
    }
}
