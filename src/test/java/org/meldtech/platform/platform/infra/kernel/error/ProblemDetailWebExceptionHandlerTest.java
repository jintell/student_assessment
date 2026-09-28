package org.meldtech.platform.platform.infra.kernel.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URI;
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
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import tools.jackson.databind.ObjectMapper;

class ProblemDetailWebExceptionHandlerTest {

    private static final CorrelationId CORRELATION_ID =
            CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV");

    @Test
    void writesSanitizedNonCacheableProblemDetails() {
        ProblemDetailWebExceptionHandler handler =
                new ProblemDetailWebExceptionHandler(mapper(), new ObjectMapper());
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/assessments/42"));
        IllegalArgumentException failure =
                new IllegalArgumentException("password=secret SELECT * FROM users");

        handler.handle(exchange, failure).block();

        assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        assertEquals(
                MediaType.APPLICATION_PROBLEM_JSON,
                exchange.getResponse().getHeaders().getContentType());
        assertEquals(
                CacheControl.noStore().getHeaderValue(),
                exchange.getResponse().getHeaders().getCacheControl());
        assertEquals(
                CORRELATION_ID.toString(),
                exchange.getResponse().getHeaders().getFirst("X-Correlation-Id"));
        String body = Objects.requireNonNull(exchange.getResponse().getBodyAsString().block());
        assertFalse(body.contains(failure.getMessage()));
    }

    @ParameterizedTest(name = "refuses {0}")
    @MethodSource("internalLeakAttempts")
    void refusesInternalDetailFromResponseBody(
            String source, String failureMessage, String forbiddenMarker) {
        ProblemDetailWebExceptionHandler handler =
                new ProblemDetailWebExceptionHandler(mapper(), new ObjectMapper());
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/assessments/42"));

        handler.handle(exchange, new IllegalArgumentException(failureMessage)).block();

        String body = Objects.requireNonNull(exchange.getResponse().getBodyAsString().block());
        assertFalse(body.contains(forbiddenMarker), source + " reached the response body");
        assertFalse(body.contains(failureMessage), source + " was copied from the exception");
    }

    private static Stream<Arguments> internalLeakAttempts() {
        return Stream.of(
                Arguments.of(
                        "exception message",
                        "exception_message_marker: internal invariant failed",
                        "exception_message_marker"),
                Arguments.of(
                        "stack trace",
                        "stack_trace_marker at internal.Service.execute(Service.java:42)",
                        "stack_trace_marker"),
                Arguments.of(
                        "SQL fragment",
                        "SELECT sql_fragment_marker FROM candidate_private",
                        "sql_fragment_marker"),
                Arguments.of(
                        "provider error text",
                        "provider_error_marker upstream request rejected",
                        "provider_error_marker"),
                Arguments.of(
                        "secret value",
                        "authorization=secret_value_marker",
                        "secret_value_marker"));
    }

    private static ProblemDetailMapper mapper() {
        String validationCode = "CBT-PLAT-VALIDATION";
        ProblemCodeDefinition validation =
                new ProblemCodeDefinition(
                        URI.create("https://errors.meld-tech.com/problems/validation"),
                        "Validation failed",
                        HttpStatus.BAD_REQUEST.value(),
                        "One or more request values are invalid.",
                        Map.of());
        return new ProblemDetailMapper(
                Map.of(validationCode, validation),
                Map.of(IllegalArgumentException.class, validationCode),
                ProblemDetailMetrics.NOOP,
                () -> CORRELATION_ID);
    }
}
