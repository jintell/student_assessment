package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class NonLocalLoggingConfigurationTest {

    @Test
    void nonLocalProfilesUseOnlyStructuredJsonConsoleLogging() throws IOException {
        List<Map<?, ?>> documents = loadApplicationYaml();
        Map<?, ?> nonLocal =
                documents.stream()
                        .filter(
                                document ->
                                        "!local"
                                                .equals(
                                                        valueAt(
                                                                document,
                                                                "spring",
                                                                "config",
                                                                "activate",
                                                                "on-profile")))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Missing non-local logging profile"));

        assertThat(valueAt(nonLocal, "logging", "structured", "format", "console"))
                .isEqualTo("logstash");
        assertThat(valueAt(nonLocal, "logging", "pattern")).isNull();
        assertThat(valueAt(nonLocal, "logging", "file")).isNull();

        documents.stream()
                .filter(
                        document ->
                                !"local"
                                        .equals(
                                                valueAt(
                                                        document,
                                                        "spring",
                                                        "config",
                                                        "activate",
                                                        "on-profile")))
                .forEach(document -> assertThat(valueAt(document, "logging", "pattern")).isNull());
    }

    private static List<Map<?, ?>> loadApplicationYaml() throws IOException {
        try (InputStream input =
                NonLocalLoggingConfigurationTest.class
                        .getClassLoader()
                        .getResourceAsStream("application.yaml")) {
            if (input == null) {
                throw new IOException("application.yaml is missing");
            }
            List<Map<?, ?>> documents = new ArrayList<>();
            for (Object document : new Yaml().loadAll(input)) {
                if (document instanceof Map<?, ?> map) {
                    documents.add(map);
                }
            }
            return List.copyOf(documents);
        }
    }

    private static @Nullable Object valueAt(Map<?, ?> document, String... path) {
        Object current = document;
        for (String segment : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }
}
