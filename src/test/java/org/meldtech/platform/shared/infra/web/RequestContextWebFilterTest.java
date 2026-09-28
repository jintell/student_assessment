package org.meldtech.platform.shared.infra.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.slf4j.LoggerFactory;
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

    @ParameterizedTest(name = "replaces {0}")
    @MethodSource("hostileCorrelationIdentifiers")
    void replacesHostileCorrelationIdWithoutWritingItToTheResponseOrLogSink(
            String source, String hostileValue) {
        ReactorContextPropagationConfiguration configuration =
                new ReactorContextPropagationConfiguration();
        Logger logger = (Logger) LoggerFactory.getLogger(RequestContextWebFilterTest.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/")
                                .header(
                                        RequestContextWebFilter.CORRELATION_ID_HEADER,
                                        hostileValue));

        configuration.enableAutomaticPropagation();
        try {
            StepVerifier.create(
                            filter.filter(
                                    exchange,
                                    ignored ->
                                            Mono.fromRunnable(
                                                    () -> logger.info("request reached handler"))))
                    .verifyComplete();

            assertEquals(
                    GENERATED,
                    exchange.getResponse()
                            .getHeaders()
                            .getFirst(RequestContextWebFilter.CORRELATION_ID_HEADER));
            assertEquals(1, appender.list.size());
            ILoggingEvent event = appender.list.getFirst();
            assertEquals(
                    GENERATED,
                    event.getMDCPropertyMap().get(RequestContextPropagation.CORRELATION_ID_KEY));
            assertFalse(event.getFormattedMessage().contains(hostileValue), source);
            assertFalse(event.getMDCPropertyMap().containsValue(hostileValue), source);
        } finally {
            configuration.disableAutomaticPropagation();
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static Stream<Arguments> hostileCorrelationIdentifiers() {
        return Stream.of(
                Arguments.of("over-long input", "A".repeat(129)),
                Arguments.of("control character", "01J9Z9Q9J6Y7TQ4PXKJ4D0M3N\u0001"),
                Arguments.of("newline", "01J9Z9Q9J6Y7TQ4PXKJ4D0M3N\nforged=true"),
                Arguments.of("JSON fragment", "{\"correlationId\":\"forged\"}"),
                Arguments.of("ANSI escape", "\u001B[31mforged\u001B[0m"));
    }

    private static ActorContext actor(String correlationId) {
        return ActorContext.tenantWorkforce(
                new ActorId("operator-123"),
                TenantId.parse("ad25adad-f989-4a62-9754-3a600e5bf347"),
                CorrelationId.parse(correlationId),
                SourceIp.parse("127.0.0.1"));
    }
}
