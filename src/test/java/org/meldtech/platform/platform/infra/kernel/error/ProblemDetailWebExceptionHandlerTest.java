package org.meldtech.platform.platform.infra.kernel.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
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
