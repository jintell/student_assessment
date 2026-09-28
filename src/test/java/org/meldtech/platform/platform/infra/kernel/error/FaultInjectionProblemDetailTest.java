package org.meldtech.platform.platform.infra.kernel.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.error.ProblemCodeDefinition;
import org.meldtech.platform.shared.kernel.error.ProblemDetailMapper;
import org.meldtech.platform.shared.kernel.error.ProblemDetailMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class FaultInjectionProblemDetailTest {

    private static final CorrelationId CORRELATION_ID =
            CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV");
    private static final Set<String> ALLOWED_FIELDS =
            Set.of("type", "title", "status", "code", "detail", "instance", "correlationId");

    @Test
    void suiteCoversEveryRequiredFaultLayer() {
        EnumSet<FaultLayer> covered =
                FaultInjectionProblemDetailTest.faultLayers()
                        .collect(
                                () -> EnumSet.noneOf(FaultLayer.class),
                                EnumSet::add,
                                EnumSet::addAll);

        assertEquals(EnumSet.allOf(FaultLayer.class), covered);
    }

    @ParameterizedTest(name = "{0} faults produce an allowlisted ProblemDetail")
    @EnumSource(FaultLayer.class)
    void everyFaultLayerProducesAnAllowlistedProblemDetail(FaultLayer layer) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ProblemDetailWebExceptionHandler handler =
                new ProblemDetailWebExceptionHandler(mapper(), objectMapper);
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/fault-injection/" + layer.path));
        RuntimeException failure = layer.inject();

        handler.handle(exchange, failure).block();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
        assertEquals(
                MediaType.APPLICATION_PROBLEM_JSON,
                exchange.getResponse().getHeaders().getContentType());
        assertEquals(
                CORRELATION_ID.toString(),
                exchange.getResponse().getHeaders().getFirst("X-Correlation-Id"));
        String responseBody =
                Objects.requireNonNull(exchange.getResponse().getBodyAsString().block());
        JsonNode body = objectMapper.readTree(responseBody);
        Set<String> emittedFields =
                body.properties().stream()
                        .map(Map.Entry::getKey)
                        .collect(java.util.stream.Collectors.toSet());
        assertEquals(ALLOWED_FIELDS, emittedFields);
        assertEquals("CBT-PLAT-INTERNAL", body.get("code").stringValue());
        assertEquals(500, body.get("status").asInt());
        assertEquals(CORRELATION_ID.toString(), body.get("correlationId").stringValue());
        assertEquals("/fault-injection/" + layer.path, body.get("instance").stringValue());
        assertFalse(responseBody.contains(layer.secretMarker));
        assertTrue(
                body.get("type")
                        .stringValue()
                        .startsWith("https://errors.meld-tech.com/problems/"));
    }

    private static Stream<FaultLayer> faultLayers() {
        return Stream.of(FaultLayer.values());
    }

    private static ProblemDetailMapper mapper() {
        ProblemCodeDefinition internal =
                new ProblemCodeDefinition(
                        URI.create("https://errors.meld-tech.com/problems/internal"),
                        "Unexpected error",
                        500,
                        "The request could not be completed.",
                        Map.of());
        return new ProblemDetailMapper(
                Map.of(ProblemDetailMapper.INTERNAL_CODE, internal),
                Map.of(),
                ProblemDetailMetrics.NOOP,
                () -> CORRELATION_ID);
    }

    private enum FaultLayer {
        FILTER("filter", "filter_secret_marker"),
        CONTROLLER("controller", "controller_secret_marker"),
        HANDLER("handler", "handler_secret_marker"),
        DOMAIN("domain", "domain_secret_marker"),
        PORT_ADAPTER("port-adapter", "port_adapter_secret_marker"),
        DATABASE("database", "database_secret_marker"),
        SERIALISATION("serialisation", "serialisation_secret_marker");

        private final String path;
        private final String secretMarker;

        FaultLayer(String path, String secretMarker) {
            this.path = path;
            this.secretMarker = secretMarker;
        }

        private RuntimeException inject() {
            return new FaultInjectionException(name() + " failed: " + secretMarker);
        }
    }

    private static final class FaultInjectionException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private FaultInjectionException(String message) {
            super(message);
        }
    }
}
