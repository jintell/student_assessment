package org.meldtech.platform.platform.infra.kernel.error;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TreeMap;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

final class ErrorCatalogueArtifacts {

    private static final List<String> BASE_REQUIRED =
            List.of("type", "title", "status", "code", "detail", "instance", "correlationId");
    private static final String EXAMPLE_INSTANCE = "/api/v1/example";
    private static final String EXAMPLE_CORRELATION_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";

    private final ObjectMapper objectMapper = new ObjectMapper();

    String runtimeJson(ErrorCatalogue catalogue) throws JacksonException {
        return objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(runtimeCatalogue(catalogue))
                + System.lineSeparator();
    }

    String openApi(ErrorCatalogue catalogue) {
        Map<String, Object> schemas = new LinkedHashMap<>();
        List<Map<String, String>> alternatives = new ArrayList<>();
        Map<String, Object> examples = new LinkedHashMap<>();
        catalogue
                .problems()
                .forEach(
                        (code, problem) -> {
                            String componentName = componentName(code);
                            schemas.put(componentName, problemSchema(code, problem));
                            alternatives.add(
                                    Map.of("$ref", "#/components/schemas/" + componentName));
                            examples.put(componentName, problemExample(code, problem));
                        });
        schemas.put("ProblemDetail", Map.of("oneOf", alternatives));

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("schemas", schemas);
        components.put("examples", examples);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("openapi", "3.1.0");
        document.put(
                "info",
                new TreeMap<>(
                        Map.of(
                                "title",
                                "CBT Platform Error Catalogue",
                                "version",
                                Integer.toString(catalogue.version()))));
        document.put("paths", Map.of());
        document.put("components", components);
        return yaml().dump(document);
    }

    String clientDocumentation(ErrorCatalogue catalogue) {
        StringBuilder markdown =
                new StringBuilder("# Error Catalogue\n\nCatalogue version: `")
                        .append(catalogue.version())
                        .append("`\n\n")
                        .append("| Code | Status | Type | Title | Detail | Extensions |\n")
                        .append("|---|---:|---|---|---|---|\n");
        catalogue
                .problems()
                .forEach(
                        (code, problem) ->
                                markdown.append("| `")
                                        .append(code)
                                        .append("` | ")
                                        .append(problem.status())
                                        .append(" | ")
                                        .append(problem.type())
                                        .append(" | ")
                                        .append(problem.title())
                                        .append(" | ")
                                        .append(problem.detail())
                                        .append(" | ")
                                        .append(String.join(", ", problem.extensions().keySet()))
                                        .append(" |\n"));
        return markdown.toString();
    }

    private static Map<String, Object> runtimeCatalogue(ErrorCatalogue catalogue) {
        Map<String, Object> problems = new LinkedHashMap<>();
        catalogue
                .problems()
                .forEach(
                        (code, problem) -> {
                            Map<String, Object> definition = new LinkedHashMap<>();
                            definition.put("type", problem.type());
                            definition.put("title", problem.title());
                            definition.put("status", problem.status());
                            definition.put("detail", problem.detail());
                            definition.put("extensions", runtimeExtensions(problem));
                            problems.put(code, definition);
                        });
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("catalogueVersion", catalogue.version());
        root.put("problems", problems);
        return root;
    }

    private static Map<String, Object> runtimeExtensions(ProblemDefinition problem) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        problem.extensions()
                .forEach(
                        (name, extension) -> {
                            Map<String, Object> definition = new LinkedHashMap<>();
                            definition.put("type", extension.type());
                            definition.put("required", extension.required());
                            definition.putAll(extension.constraints());
                            extensions.put(name, definition);
                        });
        return extensions;
    }

    private static Map<String, Object> problemSchema(String code, ProblemDefinition problem) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "type",
                new TreeMap<>(Map.of("type", "string", "format", "uri", "const", problem.type())));
        properties.put("title", new TreeMap<>(Map.of("type", "string", "const", problem.title())));
        properties.put(
                "status", new TreeMap<>(Map.of("type", "integer", "const", problem.status())));
        properties.put("code", new TreeMap<>(Map.of("type", "string", "const", code)));
        properties.put(
                "detail", new TreeMap<>(Map.of("type", "string", "const", problem.detail())));
        properties.put(
                "instance", new TreeMap<>(Map.of("type", "string", "format", "uri-reference")));
        properties.put(
                "correlationId",
                new TreeMap<>(
                        Map.of("type", "string", "pattern", "^[0-7][0-9A-HJKMNP-TV-Z]{25}$")));

        List<String> required = new ArrayList<>(BASE_REQUIRED);
        problem.extensions()
                .forEach(
                        (name, extension) -> {
                            Map<String, Object> schema = new LinkedHashMap<>();
                            schema.put("type", extension.type());
                            schema.putAll(extension.constraints());
                            properties.put(name, schema);
                            if (extension.required()) {
                                required.add(name);
                            }
                        });
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", required);
        schema.put("properties", properties);
        return schema;
    }

    private static Map<String, Object> problemExample(String code, ProblemDefinition problem) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", problem.type());
        value.put("title", problem.title());
        value.put("status", problem.status());
        value.put("code", code);
        value.put("detail", problem.detail());
        value.put("instance", EXAMPLE_INSTANCE);
        value.put("correlationId", EXAMPLE_CORRELATION_ID);
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("summary", problem.title());
        example.put("value", value);
        return example;
    }

    private static String componentName(String code) {
        StringBuilder name = new StringBuilder("ProblemDetail");
        StringTokenizer segments = new StringTokenizer(code, "-");
        while (segments.hasMoreTokens()) {
            String segment = segments.nextToken();
            name.append(segment.charAt(0))
                    .append(segment.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return name.toString();
    }

    private static Yaml yaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        return new Yaml(options);
    }
}
