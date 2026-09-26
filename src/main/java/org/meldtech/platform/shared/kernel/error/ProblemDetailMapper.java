package org.meldtech.platform.shared.kernel.error;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.CorrelationIdGenerator;
import org.meldtech.platform.shared.kernel.security.SecretFieldPattern;

public final class ProblemDetailMapper {

    public static final String INTERNAL_CODE = "CBT-PLAT-INTERNAL";
    private static final ProblemCodeDefinition BUILT_IN_INTERNAL =
            new ProblemCodeDefinition(
                    URI.create("https://errors.meld-tech.com/problems/internal"),
                    "Unexpected error",
                    500,
                    "The request could not be completed.",
                    Map.of());

    private final Map<String, ProblemCodeDefinition> catalogue;
    private final Map<Class<? extends Throwable>, String> exceptionMappings;
    private final ProblemDetailMetrics metrics;
    private final CorrelationIdGenerator correlationIds;

    public ProblemDetailMapper(
            Map<String, ProblemCodeDefinition> catalogue,
            Map<Class<? extends Throwable>, String> exceptionMappings,
            ProblemDetailMetrics metrics) {
        this(
                catalogue,
                exceptionMappings,
                metrics,
                () -> {
                    throw new IllegalStateException("No CorrelationIdGenerator configured");
                });
    }

    public ProblemDetailMapper(
            Map<String, ProblemCodeDefinition> catalogue,
            Map<Class<? extends Throwable>, String> exceptionMappings,
            ProblemDetailMetrics metrics,
            CorrelationIdGenerator correlationIds) {
        this.catalogue = Map.copyOf(Objects.requireNonNull(catalogue, "catalogue"));
        this.catalogue.forEach(
                (code, definition) ->
                        definition
                                .extensions()
                                .keySet()
                                .forEach(name -> requirePublicField(code, name)));
        this.exceptionMappings =
                Map.copyOf(Objects.requireNonNull(exceptionMappings, "exceptionMappings"));
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.correlationIds = Objects.requireNonNull(correlationIds, "correlationIds");
    }

    public ProblemDetailDocument map(Throwable failure, ProblemContext context) {
        return map(failure, context, Map.of());
    }

    public ProblemDetailDocument map(
            Throwable failure, ProblemContext context, Map<String, Object> extensions) {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(context, "context");
        String code = mappedCode(failure);
        ProblemCodeDefinition definition = catalogue.get(code);
        if (definition == null) {
            emitFallback(ProblemDetailMetrics.FallbackReason.CATALOGUE_MISS);
            code = INTERNAL_CODE;
            definition = catalogue.getOrDefault(INTERNAL_CODE, BUILT_IN_INTERNAL);
        }
        Map<String, Object> acceptedExtensions = validateExtensions(definition, extensions);
        emit(code);
        return new ProblemDetailDocument(
                definition.type(),
                definition.title(),
                definition.status(),
                code,
                definition.detail(),
                context.instance(),
                context.correlationId(),
                acceptedExtensions);
    }

    public ProblemDetailDocument mapSafely(
            Throwable failure, URI instance, Optional<String> correlationId) {
        CorrelationId validatedCorrelation = resolveCorrelation(correlationId);
        URI normalizedInstance = normalizeInstance(instance);
        try {
            return map(failure, new ProblemContext(normalizedInstance, validatedCorrelation));
        } catch (RuntimeException ignored) {
            emitFallback(ProblemDetailMetrics.FallbackReason.CATALOGUE_MISS);
            emit(INTERNAL_CODE);
            return internalDocument(normalizedInstance, validatedCorrelation);
        }
    }

    public byte[] renderMinimalFallback(URI instance, CorrelationId correlationId) {
        emitFallback(ProblemDetailMetrics.FallbackReason.SERIALIZATION_FAILURE);
        ProblemDetailDocument fallback =
                internalDocument(normalizeInstance(instance), correlationId);
        String json =
                "{\"type\":"
                        + quote(fallback.type().toString())
                        + ",\"title\":"
                        + quote(fallback.title())
                        + ",\"status\":500,\"code\":"
                        + quote(INTERNAL_CODE)
                        + ",\"detail\":"
                        + quote(fallback.detail())
                        + ",\"instance\":"
                        + quote(fallback.instance().toString())
                        + ",\"correlationId\":"
                        + quote(fallback.correlationId().toString())
                        + "}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private String mappedCode(Throwable failure) {
        return exceptionMappings.entrySet().stream()
                .filter(entry -> entry.getKey().isInstance(failure))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(INTERNAL_CODE);
    }

    private CorrelationId resolveCorrelation(Optional<String> value) {
        Objects.requireNonNull(value, "correlationId");
        if (value.filter(CorrelationId::isValid).isPresent()) {
            return CorrelationId.parse(value.orElseThrow());
        }
        emitFallback(ProblemDetailMetrics.FallbackReason.MISSING_CORRELATION);
        return correlationIds.generate();
    }

    private static URI normalizeInstance(URI instance) {
        if (instance != null && !instance.isAbsolute() && instance.getPath().startsWith("/")) {
            return URI.create(instance.getPath());
        }
        return URI.create("/");
    }

    private static ProblemDetailDocument internalDocument(
            URI instance, CorrelationId correlationId) {
        return new ProblemDetailDocument(
                BUILT_IN_INTERNAL.type(),
                BUILT_IN_INTERNAL.title(),
                BUILT_IN_INTERNAL.status(),
                INTERNAL_CODE,
                BUILT_IN_INTERNAL.detail(),
                instance,
                correlationId,
                Map.of());
    }

    private void emit(String code) {
        try {
            metrics.emitted(code);
        } catch (RuntimeException ignored) {
            // Telemetry cannot change an error response.
        }
    }

    private void emitFallback(ProblemDetailMetrics.FallbackReason reason) {
        try {
            metrics.fallback(reason);
        } catch (RuntimeException ignored) {
            // Telemetry cannot change an error response.
        }
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        value.codePoints()
                .forEach(
                        character -> {
                            switch (character) {
                                case '"' -> escaped.append("\\\"");
                                case '\\' -> escaped.append("\\\\");
                                case '\b' -> escaped.append("\\b");
                                case '\f' -> escaped.append("\\f");
                                case '\n' -> escaped.append("\\n");
                                case '\r' -> escaped.append("\\r");
                                case '\t' -> escaped.append("\\t");
                                default -> {
                                    if (character < 0x20) {
                                        escaped.append(
                                                String.format(Locale.ROOT, "\\u%04x", character));
                                    } else {
                                        escaped.appendCodePoint(character);
                                    }
                                }
                            }
                        });
        return escaped.append('"').toString();
    }

    private static Map<String, Object> validateExtensions(
            ProblemCodeDefinition definition, Map<String, Object> supplied) {
        Map<String, Object> values =
                new LinkedHashMap<>(Objects.requireNonNull(supplied, "extensions"));
        values.keySet().forEach(name -> requirePublicField("supplied extension", name));
        if (!definition.extensions().keySet().containsAll(values.keySet())) {
            throw new IllegalArgumentException(
                    "Problem extensions are not declared by the catalogue");
        }
        for (Map.Entry<String, ProblemCodeDefinition.ExtensionRule> entry :
                definition.extensions().entrySet()) {
            Object value = values.get(entry.getKey());
            if (entry.getValue().required() && value == null) {
                throw new IllegalArgumentException(
                        "Required problem extension is missing: " + entry.getKey());
            }
            if (value != null && !matches(entry.getValue().type(), value)) {
                throw new IllegalArgumentException(
                        "Problem extension has the wrong primitive type: " + entry.getKey());
            }
        }
        return Map.copyOf(values);
    }

    private static boolean matches(ProblemCodeDefinition.PrimitiveType expected, Object value) {
        return switch (expected) {
            case STRING -> value instanceof String;
            case INTEGER ->
                    value instanceof Byte
                            || value instanceof Short
                            || value instanceof Integer
                            || value instanceof Long;
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
        };
    }

    private static void requirePublicField(String source, String name) {
        if (SecretFieldPattern.isSecretField(name)) {
            throw new IllegalArgumentException(
                    source + " contains a secret-shaped problem extension: " + name);
        }
    }
}
