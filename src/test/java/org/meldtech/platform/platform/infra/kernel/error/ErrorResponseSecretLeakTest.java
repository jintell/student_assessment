package org.meldtech.platform.platform.infra.kernel.error;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.error.ProblemCodeDefinition;
import org.meldtech.platform.shared.kernel.error.ProblemDetailMapper;
import org.meldtech.platform.shared.kernel.error.ProblemDetailMetrics;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import tools.jackson.databind.ObjectMapper;

class ErrorResponseSecretLeakTest {

    private static final CorrelationId CORRELATION_ID =
            CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV");

    @Test
    void everyCatalogueEntryPassesTheKernelSecretFieldPolicy() throws Exception {
        ErrorCatalogue source =
                new ErrorCatalogueLoader()
                        .load(Path.of("src", "main", "resources", "error-catalogue.yaml"));
        Map<String, ProblemCodeDefinition> definitions = new LinkedHashMap<>();
        source.problems()
                .forEach((code, problem) -> definitions.put(code, toKernelDefinition(problem)));

        new ProblemDetailMapper(
                definitions, Map.of(), ProblemDetailMetrics.NOOP, () -> CORRELATION_ID);
    }

    @ParameterizedTest(name = "refuses {0} from an exception")
    @MethodSource("secretAndInternalDetails")
    void actualErrorBoundaryNeverReflectsAdversarialFailureDetails(
            String source, String failureDetail, String forbiddenMarker) {
        ProblemDetailWebExceptionHandler handler =
                new ProblemDetailWebExceptionHandler(mapper(), new ObjectMapper());
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/secret-leak-scan"));

        handler.handle(exchange, new IllegalArgumentException(failureDetail)).block();

        String body = Objects.requireNonNull(exchange.getResponse().getBodyAsString().block());
        assertFalse(body.contains(failureDetail), source + " was copied into the error response");
        assertFalse(body.contains(forbiddenMarker), source + " marker reached the error response");
    }

    private static Stream<Arguments> secretAndInternalDetails() {
        return Stream.of(
                Arguments.of(
                        "stack trace",
                        "stack_trace_leak at internal.Service.run(Service.java:42)",
                        "stack_trace_leak"),
                Arguments.of(
                        "SQL fragment",
                        "SELECT sql_fragment_leak FROM candidate_private",
                        "sql_fragment_leak"),
                Arguments.of(
                        "provider diagnostic",
                        "provider_error_leak upstream account rejected",
                        "provider_error_leak"),
                Arguments.of(
                        "authorization token",
                        "Authorization: Bearer secret_token_leak",
                        "secret_token_leak"),
                Arguments.of("password", "password=secret_password_leak", "secret_password_leak"));
    }

    private static ProblemDetailMapper mapper() {
        String validationCode = "CBT-PLAT-VALIDATION";
        ProblemCodeDefinition validation =
                new ProblemCodeDefinition(
                        URI.create("https://errors.meld-tech.com/problems/validation"),
                        "Validation failed",
                        400,
                        "One or more request values are invalid.",
                        Map.of());
        return new ProblemDetailMapper(
                Map.of(validationCode, validation),
                Map.of(IllegalArgumentException.class, validationCode),
                ProblemDetailMetrics.NOOP,
                () -> CORRELATION_ID);
    }

    private static ProblemCodeDefinition toKernelDefinition(ProblemDefinition source) {
        Map<String, ProblemCodeDefinition.ExtensionRule> extensions = new LinkedHashMap<>();
        source.extensions()
                .forEach(
                        (name, definition) ->
                                extensions.put(
                                        name,
                                        new ProblemCodeDefinition.ExtensionRule(
                                                ProblemCodeDefinition.PrimitiveType.valueOf(
                                                        definition
                                                                .type()
                                                                .toUpperCase(
                                                                        java.util.Locale.ROOT)),
                                                definition.required())));
        return new ProblemCodeDefinition(
                URI.create(source.type()),
                source.title(),
                source.status(),
                source.detail(),
                extensions);
    }
}
