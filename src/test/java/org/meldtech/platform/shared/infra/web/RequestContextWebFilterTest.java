package org.meldtech.platform.shared.infra.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.api.RequestCarrier;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RequestContextWebFilterTest {

    private final RequestContextWebFilter filter = new RequestContextWebFilter();

    @Test
    void seedsAndReturnsAValidSuppliedCorrelationId() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/")
                                .header(
                                        RequestContextWebFilter.CORRELATION_ID_HEADER,
                                        "request-123"));
        AtomicReference<RequestCarrier> captured = new AtomicReference<>();
        WebFilterChain chain =
                ignored ->
                        Mono.deferContextual(
                                context -> {
                                    captured.set(context.get(RequestCarrier.class));
                                    return Mono.empty();
                                });

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        RequestCarrier carrier = Objects.requireNonNull(captured.get());
        assertEquals("request-123", carrier.correlationId());
        assertEquals(
                "request-123",
                exchange.getResponse()
                        .getHeaders()
                        .getFirst(RequestContextWebFilter.CORRELATION_ID_HEADER));
    }

    @Test
    void replacesAnInvalidCorrelationId() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/")
                                .header(
                                        RequestContextWebFilter.CORRELATION_ID_HEADER,
                                        "invalid value"));
        AtomicReference<RequestCarrier> captured = new AtomicReference<>();

        StepVerifier.create(
                        filter.filter(
                                exchange,
                                ignored ->
                                        Mono.deferContextual(
                                                context -> {
                                                    captured.set(context.get(RequestCarrier.class));
                                                    return Mono.empty();
                                                })))
                .verifyComplete();

        RequestCarrier carrier = Objects.requireNonNull(captured.get());
        assertNotEquals("invalid value", carrier.correlationId());
        assertEquals(
                carrier.correlationId(),
                exchange.getResponse()
                        .getHeaders()
                        .getFirst(RequestContextWebFilter.CORRELATION_ID_HEADER));
    }
}
