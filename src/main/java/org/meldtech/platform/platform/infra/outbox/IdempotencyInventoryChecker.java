package org.meldtech.platform.platform.infra.outbox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

public final class IdempotencyInventoryChecker {

    private static final Pattern FEATURE_ID = Pattern.compile("FEAT-[A-Z]+-[0-9]{3}");
    private static final Set<String> BASELINE_OPERATIONS =
            Set.of(
                    "answer-submission",
                    "attempt-submission",
                    "pin-issuance",
                    "grading-execution",
                    "result-publication",
                    "correction-application",
                    "notification-dispatch-credential",
                    "notification-dispatch-provider-submission",
                    "provider-webhook",
                    "outbox-publication",
                    "auto-submit-sweep",
                    "bulk-student-upload",
                    "generic-post-transition");

    private IdempotencyInventoryChecker() {}

    public static void main(String[] arguments) {
        if (arguments.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: IdempotencyInventoryChecker <inventory> <feature-status> <repo-root>");
        }
        verify(Path.of(arguments[0]), Path.of(arguments[1]), Path.of(arguments[2]));
    }

    static void verify(Path inventoryPath, Path featureStatusPath, Path repositoryRoot) {
        Object inventoryRoot = loadYaml(inventoryPath);
        Object statusRoot = loadYaml(featureStatusPath);
        Set<String> shippedFeatures = shippedFeatures(statusRoot);
        if (!(inventoryRoot instanceof Map<?, ?> inventory)
                || !(inventory.get("operations") instanceof Iterable<?> operations)) {
            throw new IllegalStateException(
                    "IDEMPOTENCY_INVENTORY_INVALID: operations are required");
        }
        Set<String> operationIds = new HashSet<>();
        for (Object operation : operations) {
            if (!(operation instanceof Map<?, ?> row)) {
                throw new IllegalStateException("IDEMPOTENCY_INVENTORY_INVALID: row is not a map");
            }
            String id = required(row, "id");
            if (!operationIds.add(id)) {
                throw invalid(id, "duplicate id");
            }
            required(row, "operation");
            required(row, "key");
            required(row, "store");
            String owner = required(row, "owningFeature");
            if (!FEATURE_ID.matcher(owner).matches()) {
                throw invalid(id, "owningFeature must be a feature id");
            }
            if (shippedFeatures.contains(owner)) {
                String provingTest = required(row, "provingTest");
                if (!"implemented".equals(required(row, "proofStatus"))) {
                    throw invalid(id, "shipped owner requires proofStatus implemented");
                }
                verifyTestExists(id, provingTest, repositoryRoot);
            }
        }
        if (!operationIds.containsAll(BASELINE_OPERATIONS)) {
            Set<String> missing = new HashSet<>(BASELINE_OPERATIONS);
            missing.removeAll(operationIds);
            throw new IllegalStateException(
                    "IDEMPOTENCY_INVENTORY_INVALID: missing baseline operations " + missing);
        }
    }

    private static Set<String> shippedFeatures(Object root) {
        if (!(root instanceof Map<?, ?> status)
                || !(status.get("shippedFeatures") instanceof Iterable<?> shipped)) {
            throw new IllegalStateException(
                    "IDEMPOTENCY_INVENTORY_INVALID: shippedFeatures are required");
        }
        Set<String> result = new HashSet<>();
        for (Object feature : shipped) {
            String value = String.valueOf(feature);
            if (!FEATURE_ID.matcher(value).matches()) {
                throw new IllegalStateException("Invalid shipped feature id " + value);
            }
            result.add(value);
        }
        return Set.copyOf(result);
    }

    private static void verifyTestExists(String operationId, String provingTest, Path root) {
        int methodSeparator = provingTest.lastIndexOf('.');
        if (methodSeparator < 1) {
            throw invalid(operationId, "provingTest must name a class and method");
        }
        String className = provingTest.substring(0, methodSeparator);
        String methodName = provingTest.substring(methodSeparator + 1);
        Path source = root.resolve("src/test/java").resolve(className.replace('.', '/') + ".java");
        if (!Files.isRegularFile(source)) {
            throw invalid(operationId, "proving test class does not exist: " + className);
        }
        try {
            if (!Files.readString(source).contains(methodName + "(")) {
                throw invalid(operationId, "proving test method does not exist: " + provingTest);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot inspect proving test " + source, exception);
        }
    }

    private static String required(Map<?, ?> row, String key) {
        @Nullable Object value = row.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException(
                    "IDEMPOTENCY_INVENTORY_INVALID: non-blank " + key + " is required");
        }
        return text;
    }

    private static Object loadYaml(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Missing inventory input " + path);
        }
        try (InputStream input = Files.newInputStream(path)) {
            return new Yaml().load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read inventory input " + path, exception);
        }
    }

    private static IllegalStateException invalid(String operationId, String detail) {
        return new IllegalStateException(
                "IDEMPOTENCY_INVENTORY_INVALID: " + operationId + " " + detail);
    }
}
