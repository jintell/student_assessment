package org.meldtech.platform.platform.infra.kernel.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ProblemDetailConstructionSiteTest {

    private static final Path PRODUCTION_JAVA = Path.of("src", "main", "java");
    private static final Set<String> RESPONSE_WRITERS =
            Set.of(
                    "ProblemDetailWebExceptionHandler.java",
                    "IdempotencyResponseCapture.java",
                    "StoredResponseReplayer.java");

    @Test
    void mapperAndReviewedTransportAdaptersAreTheOnlyConstructionAndWriteSites() throws Exception {
        assertSourcePolicy(productionSources());
    }

    @Test
    void aFilterWritingAnErrorBodyDirectlyFailsTheGate() {
        Map<Path, String> fixture =
                Map.of(
                        Path.of("src/main/java/example/RogueErrorFilter.java"),
                        """
                        final class RogueErrorFilter {
                            Mono<Void> filter(ServerWebExchange exchange) {
                                exchange.getResponse().getHeaders()
                                    .setContentType(MediaType.APPLICATION_PROBLEM_JSON);
                                return exchange.getResponse().writeWith(Mono.empty());
                            }
                        }
                        """);

        assertThatThrownBy(() -> assertSourcePolicy(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("RogueErrorFilter.java")
                .hasMessageContaining("direct response write")
                .hasMessageContaining("problem JSON write");
    }

    private static Map<Path, String> productionSources() throws IOException {
        try (Stream<Path> files = Files.walk(PRODUCTION_JAVA)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .collect(
                            Collectors.toMap(
                                    path -> path,
                                    path -> {
                                        try {
                                            return Files.readString(path);
                                        } catch (IOException exception) {
                                            throw new SourceReadException(path, exception);
                                        }
                                    }));
        } catch (SourceReadException exception) {
            throw exception.ioCause();
        }
    }

    private static void assertSourcePolicy(Map<Path, String> sources) {
        List<String> violations = new ArrayList<>();
        sources.forEach(
                (path, source) -> {
                    String file = path.getFileName().toString();
                    if (source.contains("new ProblemDetailDocument(")
                            && !file.equals("ProblemDetailMapper.java")) {
                        violations.add(path + ": ProblemDetail construction outside mapper");
                    }
                    if (source.contains("writeWith(") && !RESPONSE_WRITERS.contains(file)) {
                        violations.add(path + ": direct response write outside reviewed adapter");
                    }
                    if (source.contains("APPLICATION_PROBLEM_JSON")
                            && !file.equals("ProblemDetailWebExceptionHandler.java")) {
                        violations.add(path + ": problem JSON write outside global handler");
                    }
                });
        assertThat(violations).as("problem response construction policy violations").isEmpty();
    }

    private static final class SourceReadException extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final IOException ioCause;

        private SourceReadException(Path source, IOException cause) {
            super("Unable to read " + source, cause);
            this.ioCause = cause;
        }

        private IOException ioCause() {
            return ioCause;
        }
    }
}
