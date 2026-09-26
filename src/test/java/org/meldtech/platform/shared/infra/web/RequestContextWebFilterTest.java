package org.meldtech.platform.shared.infra.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RequestContextWebFilterTest {

    private static final String SUPPLIED = "01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV";
    private static final String GENERATED = "01J9Z9Q9J6Y7TQ4PXKJ4D0M3NW";
    private final RequestContextPropagation propagation = new RequestContextPropagation();
    private final RequestContextWebFilter filter =
            new RequestContextWebFilter(() -> CorrelationId.parse(GENERATED), propagation);

    @Test
    void seedsAndReturnsAValidSuppliedCorrelationId() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/")
                                .header(RequestContextWebFilter.CORRELATION_ID_HEADER, SUPPLIED));
        ActorContext actor = actor(SUPPLIED);
        propagation.attach(exchange, actor);
        AtomicReference<ActorContext> captured = new AtomicReference<>();
        WebFilterChain chain =
                ignored ->
                        Mono.deferContextual(
                                context -> {
                                    captured.set(context.get(ActorContext.class));
                                    return Mono.empty();
                                });

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        ActorContext carrier = Objects.requireNonNull(captured.get());
        assertEquals(SUPPLIED, carrier.correlationId().toString());
        assertEquals(
                SUPPLIED,
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
        propagation.attach(exchange, actor(GENERATED));
        AtomicReference<ActorContext> captured = new AtomicReference<>();

        StepVerifier.create(
                        filter.filter(
                                exchange,
                                ignored ->
                                        Mono.deferContextual(
                                                context -> {
                                                    captured.set(context.get(ActorContext.class));
                                                    return Mono.empty();
                                                })))
                .verifyComplete();

        ActorContext carrier = Objects.requireNonNull(captured.get());
        assertNotEquals("invalid value", carrier.correlationId().toString());
        assertEquals(GENERATED, carrier.correlationId().toString());
        assertEquals(
                carrier.correlationId().toString(),
                exchange.getResponse()
                        .getHeaders()
                        .getFirst(RequestContextWebFilter.CORRELATION_ID_HEADER));
    }

    @Test
    void replacesMultipleCorrelationHeadersWithoutEchoingEitherValue() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/")
                                .header(
                                        RequestContextWebFilter.CORRELATION_ID_HEADER,
                                        SUPPLIED,
                                        "01J9Z9Q9J6Y7TQ4PXKJ4D0M3NX"));

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.empty())).verifyComplete();

        assertEquals(
                GENERATED,
                exchange.getResponse()
                        .getHeaders()
                        .getFirst(RequestContextWebFilter.CORRELATION_ID_HEADER));
    }

    private static ActorContext actor(String correlationId) {
        return ActorContext.tenantWorkforce(
                new ActorId("operator-123"),
                TenantId.parse("ad25adad-f989-4a62-9754-3a600e5bf347"),
                CorrelationId.parse(correlationId),
                SourceIp.parse("127.0.0.1"));
    }
}
