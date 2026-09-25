package org.meldtech.platform.platform.infra.kernel.error;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

final class ErrorCatalogueLoader {

    private static final String TYPE_URI_BASE = "https://errors.meld-tech.com/problems/";
    private static final Pattern CODE = Pattern.compile("^CBT-PLAT-[A-Z0-9]+(?:-[A-Z0-9]+)*$");
    private static final Set<String> ROOT_FIELDS = Set.of("catalogueVersion", "problems");
    private static final Set<String> PROBLEM_FIELDS =
            Set.of("type", "title", "status", "detail", "extensions");
    private static final Set<String> EXTENSION_FIELDS =
            Set.of(
                    "type",
                    "required",
                    "minimum",
                    "maximum",
                    "minLength",
                    "maxLength",
                    "pattern",
                    "enum");
    private static final Set<String> EXTENSION_TYPES =
            Set.of("string", "integer", "number", "boolean");
    private static final Set<String> BASE_FIELDS =
            Set.of("type", "title", "status", "code", "detail", "instance", "correlationId");

    ErrorCatalogue load(Path source) throws IOException {
        Object loaded;
        try (Reader reader = Files.newBufferedReader(source)) {
            loaded = Objects.requireNonNull(new Yaml().load(reader), "error catalogue is empty");
        }

        Map<?, ?> root = requireMap(loaded, "catalogue");
        requireExactFields(root, ROOT_FIELDS, "catalogue");
        int version =
                requireInteger(
                        requiredValue(root, "catalogueVersion", "catalogue"), "catalogueVersion");
        if (version < 1) {
            throw invalid("catalogueVersion must be positive");
        }

        Map<?, ?> problemValues =
                requireMap(requiredValue(root, "problems", "catalogue"), "problems");
        if (problemValues.isEmpty()) {
            throw invalid("problems must not be empty");
        }

        Map<String, ProblemDefinition> problems = new TreeMap<>();
        Set<String> typeUris = new HashSet<>();
        for (Map.Entry<?, ?> entry : problemValues.entrySet()) {
            String code = requireCode(entry.getKey());
            ProblemDefinition problem = readProblem(code, entry.getValue());
            if (!typeUris.add(problem.type())) {
                throw invalid("duplicate problem type URI: " + problem.type());
            }
            problems.put(code, problem);
        }

        ProblemDefinition generic = problems.get("CBT-PLAT-INTERNAL");
        if (generic == null || !generic.extensions().isEmpty()) {
            throw invalid("CBT-PLAT-INTERNAL is required and cannot declare extensions");
        }
        return new ErrorCatalogue(version, problems);
    }

    private ProblemDefinition readProblem(String code, Object value) {
        Map<?, ?> fields = requireMap(value, code);
        requireExactFields(fields, PROBLEM_FIELDS, code);
        String type = requireTypeUri(requiredValue(fields, "type", code), code);
        String title = requireText(requiredValue(fields, "title", code), code + ".title");
        int status = requireInteger(requiredValue(fields, "status", code), code + ".status");
        if (status < 400 || status > 599) {
            throw invalid(code + ".status must be between 400 and 599");
        }
        String detail = requireText(requiredValue(fields, "detail", code), code + ".detail");
        Map<String, ExtensionDefinition> extensions =
                readExtensions(
                        code,
                        requireMap(
                                requiredValue(fields, "extensions", code), code + ".extensions"));
        return new ProblemDefinition(type, title, status, detail, extensions);
    }

    private Map<String, ExtensionDefinition> readExtensions(String code, Map<?, ?> values) {
        Map<String, ExtensionDefinition> extensions = new TreeMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String name = requireText(entry.getKey(), code + ".extension name");
            if (BASE_FIELDS.contains(name)) {
                throw invalid(code + " cannot redefine base field " + name);
            }
            Map<?, ?> fields = requireMap(entry.getValue(), code + ".extensions." + name);
            requireAllowedFields(fields, EXTENSION_FIELDS, code + ".extensions." + name);
            String type =
                    requireText(
                            requiredValue(fields, "type", code + ".extensions." + name),
                            name + ".type");
            if (!EXTENSION_TYPES.contains(type)) {
                throw invalid(name + ".type is not an approved primitive type");
            }
            Object requiredValue = fields.get("required");
            if (!(requiredValue instanceof Boolean required)) {
                throw invalid(name + ".required must be a boolean");
            }
            Map<String, Object> constraints = new LinkedHashMap<>();
            fields.forEach(
                    (key, constraint) -> {
                        String fieldName = requireText(key, name + ".constraint");
                        if (!fieldName.equals("type") && !fieldName.equals("required")) {
                            constraints.put(fieldName, Objects.requireNonNull(constraint));
                        }
                    });
            extensions.put(name, new ExtensionDefinition(type, required, constraints));
        }
        return extensions;
    }

    private static String requireCode(Object value) {
        String code = requireText(value, "problem code");
        if (!CODE.matcher(code).matches()) {
            throw invalid("invalid problem code: " + code);
        }
        return code;
    }

    private static String requireTypeUri(Object value, String code) {
        String type = requireText(value, code + ".type");
        URI uri = URI.create(type);
        if (!uri.isAbsolute() || !type.startsWith(TYPE_URI_BASE)) {
            throw invalid(code + ".type must be below " + TYPE_URI_BASE);
        }
        return type;
    }

    private static void requireExactFields(Map<?, ?> values, Set<String> fields, String path) {
        requireAllowedFields(values, fields, path);
        Set<String> actual = stringKeys(values, path);
        if (!actual.containsAll(fields)) {
            Set<String> missing = new HashSet<>(fields);
            missing.removeAll(actual);
            throw invalid(path + " is missing fields " + missing);
        }
    }

    private static void requireAllowedFields(Map<?, ?> values, Set<String> fields, String path) {
        Set<String> unexpected = stringKeys(values, path);
        unexpected.removeAll(fields);
        if (!unexpected.isEmpty()) {
            throw invalid(path + " contains unsupported fields " + unexpected);
        }
    }

    private static Set<String> stringKeys(Map<?, ?> values, String path) {
        Set<String> keys = new HashSet<>();
        for (Object key : values.keySet()) {
            keys.add(requireText(key, path + " field"));
        }
        return keys;
    }

    private static Map<?, ?> requireMap(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw invalid(path + " must be a map");
    }

    private static Object requiredValue(Map<?, ?> values, String field, String path) {
        Object value = values.get(field);
        if (value == null) {
            throw invalid(path + " is missing required field " + field);
        }
        return value;
    }

    private static int requireInteger(Object value, String path) {
        if (value instanceof Integer integer) {
            return integer;
        }
        throw invalid(path + " must be an integer");
    }

    private static String requireText(Object value, String path) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw invalid(path + " must be non-blank text");
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid error catalogue: " + message);
    }
}
