package org.meldtech.platform.shared.infra.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.test.simple.SimpleTracer;
import io.micrometer.tracing.test.simple.TracerAssert;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.error.ProblemCodeDefinition;
import org.meldtech.platform.shared.kernel.error.ProblemDetailDocument;
import org.meldtech.platform.shared.kernel.error.ProblemDetailMapper;
import org.meldtech.platform.shared.kernel.error.ProblemDetailMetrics;
import org.slf4j.LoggerFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CorrelationIdLifecycleTest {

    private static final String SUPPLIED = "01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV";
    private static final String GENERATED = "01J9Z9Q9J6Y7TQ4PXKJ4D0M3NW";

    @ParameterizedTest(name = "{0} correlation id joins response, log, span and problem")
    @MethodSource("correlationInputs")
    void correlationIdJoinsEveryDiagnosticSurface(
            String source, Optional<String> suppliedHeader, String expected) {
        RequestContextWebFilter filter =
                new RequestContextWebFilter(
                        () -> CorrelationId.parse(GENERATED), new RequestContextPropagation());
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/join-test");
        suppliedHeader.ifPresent(
                value -> request.header(RequestContextWebFilter.CORRELATION_ID_HEADER, value));
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        ReactorContextPropagationConfiguration propagation =
                new ReactorContextPropagationConfiguration();
        SimpleTracer tracer = new SimpleTracer();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationHandler(new DefaultTracingObservationHandler(tracer));
        AtomicReference<ProblemDetailDocument> problem = new AtomicReference<>();
        Logger logger = (Logger) LoggerFactory.getLogger(CorrelationIdLifecycleTest.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        propagation.enableAutomaticPropagation();
        try {
            StepVerifier.create(
                            filter.filter(
                                    exchange,
                                    ignored ->
                                            Mono.deferContextual(
                                                    context -> {
                                                        String correlationId =
                                                                context.get(
                                                                        RequestContextPropagation
                                                                                .CORRELATION_ID_KEY);
                                                        problem.set(
                                                                mapper().mapSafely(
                                                                                new IllegalStateException(
                                                                                        "hidden"),
                                                                                URI.create(
                                                                                        "/join-test"),
                                                                                Optional.of(
                                                                                        correlationId)));
                                                        Observation.createNotStarted(
                                                                        "correlation.join",
                                                                        registry)
                                                                .highCardinalityKeyValue(
                                                                        "correlationId",
                                                                        correlationId)
                                                                .observe(
                                                                        () ->
                                                                                logger.info(
                                                                                        "joined diagnostic surfaces"));
                                                        return Mono.empty();
                                                    })))
                    .verifyComplete();

            assertEquals(
                    expected,
                    exchange.getResponse()
                            .getHeaders()
                            .getFirst(RequestContextWebFilter.CORRELATION_ID_HEADER));
            ProblemDetailDocument emittedProblem = Objects.requireNonNull(problem.get());
            assertEquals(expected, emittedProblem.correlationId().toString());
            ILoggingEvent logEvent = appender.list.getFirst();
            assertEquals(
                    expected,
                    logEvent.getMDCPropertyMap().get(RequestContextPropagation.CORRELATION_ID_KEY));
            TracerAssert.assertThat(tracer)
                    .onlySpan()
                    .hasNameEqualTo("correlation.join")
                    .hasTag("correlationId", expected);
            suppliedHeader
                    .filter(value -> !value.equals(expected))
                    .ifPresent(
                            rejected -> {
                                assertFalse(logEvent.getMDCPropertyMap().containsValue(rejected));
                                assertFalse(
                                        emittedProblem
                                                .correlationId()
                                                .toString()
                                                .contains(rejected));
                            });
        } finally {
            propagation.disableAutomaticPropagation();
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static Stream<Arguments> correlationInputs() {
        return Stream.of(
                Arguments.of("valid", Optional.of(SUPPLIED), SUPPLIED),
                Arguments.of("invalid", Optional.of("invalid-correlation-id"), GENERATED),
                Arguments.of("absent", Optional.empty(), GENERATED));
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
                () -> CorrelationId.parse(GENERATED));
    }
}
