package org.meldtech.platform.platform.infra.observability;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class TelemetrySchemaGate {

    private static final String FAILURE_PREFIX = "ARC-OBS-002 telemetry schema violated: ";
    private static final Set<Class<?>> SAFE_SCALARS =
            Set.of(
                    boolean.class,
                    int.class,
                    long.class,
                    Boolean.class,
                    Integer.class,
                    Long.class,
                    String.class,
                    java.time.Instant.class);

    private TelemetrySchemaGate() {}

    public static void main(String[] arguments) {
        if (arguments.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: TelemetrySchemaGate <logging-root-type> <metric-cardinality-contract>");
        }
        verifyLoggingType(load(arguments[0]));
        verifySpanAttributes();
        verifyMetricLabels(Path.of(arguments[1]));
    }

    public static void verifyLoggingType(Class<?> rootType) {
        inspect(rootType, rootType.getName(), new HashSet<>());
    }

    static void verifySpanAttributes() {
        verifyFieldNames(
                "span attribute",
                java.util.Arrays.stream(SpanAttributeName.values())
                        .map(SpanAttributeName::key)
                        .toList());
    }

    static void verifyMetricLabels(Path cardinalityContract) {
        JsonNode contract = ObservabilityContractFiles.readObject(cardinalityContract);
        JsonNode metrics =
                ObservabilityContractFiles.required(contract, "metrics", cardinalityContract);
        if (!metrics.isArray()) {
            throw violation(cardinalityContract.toString(), "metrics must be an array");
        }
        Set<String> labels = new LinkedHashSet<>();
        for (JsonNode metric : metrics) {
            JsonNode metricLabels =
                    ObservabilityContractFiles.required(metric, "labels", cardinalityContract);
            if (!metricLabels.isArray()) {
                throw violation(cardinalityContract.toString(), "metric labels must be arrays");
            }
            for (JsonNode label : metricLabels) {
                if (!label.isString()) {
                    throw violation(cardinalityContract.toString(), "metric label must be text");
                }
                labels.add(label.stringValue());
            }
        }
        verifyFieldNames("metric label", labels);
    }

    static void verifyFieldNames(String surface, Iterable<String> fieldNames) {
        for (String fieldName : fieldNames) {
            if (fieldName == null || fieldName.isBlank()) {
                throw violation(surface, "contains a blank field name");
            }
            if (TelemetryFieldPolicy.isForbidden(fieldName)) {
                throw violation(surface + " " + fieldName, "has a forbidden telemetry field name");
            }
        }
    }

    private static void inspect(Type type, String path, Set<Type> visited) {
        if (!visited.add(type)) {
            return;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            inspectParameterized(parameterizedType, path, visited);
            return;
        }
        if (!(type instanceof Class<?> rawType)) {
            throw violation(path, "has an unresolved generic telemetry type");
        }
        if (rawType == Object.class || Map.class.isAssignableFrom(rawType)) {
            throw violation(path, "uses Object or a map as an unbounded logging escape hatch");
        }
        if (rawType.getPackageName().contains(".domain")) {
            throw violation(path, "exposes domain type " + rawType.getName() + " to logging");
        }
        if (SAFE_SCALARS.contains(rawType) || rawType.isEnum()) {
            return;
        }
        if (rawType.isArray()) {
            inspect(rawType.componentType(), path + "[]", visited);
            return;
        }
        if (!rawType.isRecord()) {
            if (isApprovedOpaqueKernelValue(rawType)) {
                return;
            }
            throw violation(path, "uses non-record telemetry type " + rawType.getName());
        }
        for (RecordComponent component : rawType.getRecordComponents()) {
            String componentPath = path + "." + component.getName();
            if (TelemetryFieldPolicy.isForbidden(component.getName())) {
                throw violation(componentPath, "has a secret-bearing logging field name");
            }
            inspect(component.getGenericType(), componentPath, visited);
        }
    }

    private static void inspectParameterized(
            ParameterizedType type, String path, Set<Type> visited) {
        if (!(type.getRawType() instanceof Class<?> rawType)) {
            throw violation(path, "has an unresolved generic telemetry container");
        }
        if (rawType != Optional.class && rawType != java.util.List.class) {
            String container =
                    Collection.class.isAssignableFrom(rawType)
                                    || Map.class.isAssignableFrom(rawType)
                            ? rawType.getName()
                            : type.getTypeName();
            throw violation(path, "uses unapproved telemetry container " + container);
        }
        Type[] arguments = type.getActualTypeArguments();
        if (arguments.length != 1) {
            throw violation(path, "has an invalid telemetry container arity");
        }
        inspect(arguments[0], path + "[]", visited);
    }

    private static boolean isApprovedOpaqueKernelValue(Class<?> type) {
        String packageName = type.getPackageName();
        return packageName.equals("org.meldtech.platform.shared.kernel.identity")
                || packageName.equals("org.meldtech.platform.shared.kernel.context");
    }

    private static Class<?> load(String typeName) {
        try {
            return Class.forName(typeName);
        } catch (ClassNotFoundException exception) {
            throw violation(typeName, "cannot be loaded from the compiled application");
        }
    }

    private static IllegalStateException violation(String path, String detail) {
        return new IllegalStateException(FAILURE_PREFIX + path + " " + detail);
    }
}
