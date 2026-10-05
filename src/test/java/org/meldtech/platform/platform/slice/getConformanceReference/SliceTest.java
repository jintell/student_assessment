package org.meldtech.platform.platform.slice.getConformanceReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.api.common.AttributeKey;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.infra.observability.OpenTelemetryRequestTelemetry;
import org.meldtech.platform.shared.api.PolicyDecision;
import org.meldtech.platform.shared.api.PolicyResolver;
import org.meldtech.platform.shared.infra.web.RequestContextPropagation;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.CorrelationIdGenerator;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.meldtech.platform.shared.kernel.observability.RequestTelemetry;
import org.meldtech.platform.testing.observability.ObservabilityTestFixture;
import org.reactivestreams.Publisher;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SliceTest {

    private static final String CORRELATION_ID = "01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV";
    private static final TenantId TENANT_ID =
            TenantId.parse("ad25adad-f989-4a62-9754-3a600e5bf347");
    private static final ActorContext TENANT_REQUEST =
            ActorContext.tenantWorkforce(
                    new ActorId("operator-123"),
                    TENANT_ID,
                    CorrelationId.parse(CORRELATION_ID),
                    SourceIp.parse("127.0.0.1"));
    private static final Queries.ConformanceMetadata METADATA =
            new Queries.ConformanceMetadata(
                    "test-version",
                    "1.4",
                    "aa7fdc5bd7b4a558e3fea19cf9c69b65ed64582b",
                    12,
                    4,
                    List.of("R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8"));

    @Test
    void returnsConformanceMetadataAtTheHandlerBoundary() {
        Queries queries = tenantId -> Mono.just(METADATA);
        Handler handler = new Handler(queries);

        StepVerifier.create(handler.handle(TENANT_REQUEST, Request.INSTANCE))
                .assertNext(
                        response -> {
                            assertEquals("test-version", response.applicationVersion());
                            assertEquals(12, response.contextModuleCount());
                            assertEquals(METADATA.rules(), response.rules());
                        })
                .verifyComplete();
    }

    @Test
    void rejectsAHandlerInvocationWithoutTenantContext() {
        ActorContext actor =
                ActorContext.platformWorkforce(
                        new ActorId("operator-123"),
                        CorrelationId.parse(CORRELATION_ID),
                        SourceIp.parse("127.0.0.1"));
        Handler handler = new Handler(tenantId -> Mono.just(METADATA));

        StepVerifier.create(handler.handle(actor, Request.INSTANCE))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void productionPolicyDeniesUntilTheAuthoritativeEvaluatorExists() {
        Policy policy = new Policy();

        StepVerifier.create(policy.evaluate(TENANT_REQUEST, Request.INSTANCE))
                .expectNext(PolicyDecision.DENY)
                .verifyComplete();
    }

    @Test
    void endpointDeniesWithoutRequestContext() {
        Endpoint endpoint =
                new Endpoint(
                        policyResolver(PolicyDecision.ALLOW),
                        new Handler(unusedQueries()),
                        passthroughTelemetry());

        WebTestClient.bindToRouterFunction(endpoint)
                .build()
                .get()
                .uri(Endpoint.PATH)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .isEmpty();
    }

    @Test
    void endpointDelegatesPolicyDenialToTheUniformErrorBoundary() {
        Endpoint endpoint =
                new Endpoint(
                        policyResolver(PolicyDecision.DENY),
                        new Handler(unusedQueries()),
                        passthroughTelemetry());

        clientWithRequestContext(endpoint)
                .get()
                .uri(Endpoint.PATH)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectHeader()
                .doesNotExist("Content-Type")
                .expectBody()
                .isEmpty();
    }

    @Test
    void endpointReturnsMetadataWhenPolicyAllows() {
        Endpoint endpoint =
                new Endpoint(
                        policyResolver(PolicyDecision.ALLOW),
                        new Handler(tenantId -> Mono.just(METADATA)),
                        passthroughTelemetry());

        clientWithRequestContext(endpoint)
                .get()
                .uri(Endpoint.PATH)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.APPLICATION_JSON)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.applicationVersion")
                .isEqualTo(METADATA.applicationVersion())
                .jsonPath("$.contextModuleCount")
                .isEqualTo(12)
                .jsonPath("$.rules.length()")
                .isEqualTo(8);

        assertEquals(Endpoint.ROUTE_ID, endpoint.routeId());
    }

    @Test
    void emitsOneSpanAtTheSliceBoundary() {
        try (ObservabilityTestFixture telemetry = ObservabilityTestFixture.create(Endpoint.class)) {
            Endpoint endpoint =
                    new Endpoint(
                            policyResolver(PolicyDecision.ALLOW),
                            new Handler(tenantId -> Mono.just(METADATA)),
                            new OpenTelemetryRequestTelemetry(
                                    telemetry.openTelemetry(), () -> java.time.Instant.EPOCH));

            clientWithRequestContext(endpoint)
                    .get()
                    .uri(Endpoint.PATH)
                    .exchange()
                    .expectStatus()
                    .isOk();

            var span = telemetry.finishedSpans().getFirst();
            assertEquals(Endpoint.SPAN_NAME, span.getName());
            assertEquals("platform", span.getAttributes().get(AttributeKey.stringKey("module")));
            assertEquals(
                    "getConformanceReference",
                    span.getAttributes().get(AttributeKey.stringKey("slice")));
            assertEquals("operator", span.getAttributes().get(AttributeKey.stringKey("audience")));
            assertEquals(
                    "workforce_user",
                    span.getAttributes().get(AttributeKey.stringKey("actorType")));
            assertEquals("read", span.getAttributes().get(AttributeKey.stringKey("operation")));
            assertEquals(
                    TENANT_REQUEST.correlationId().toString(),
                    span.getAttributes().get(AttributeKey.stringKey("correlationId")));
            assertEquals(
                    TENANT_ID.toString(),
                    span.getAttributes().get(AttributeKey.stringKey("tenantId")));
        }
    }

    @Test
    void propagatesCorrelationIdFromFilterToLogAndSpan() {
        Logger logger = (Logger) LoggerFactory.getLogger(Endpoint.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try (AnnotationConfigApplicationContext context =
                        new AnnotationConfigApplicationContext(
                                ContextPropagationTestConfiguration.class);
                ObservabilityTestFixture telemetry =
                        ObservabilityTestFixture.create(Endpoint.class)) {
            Endpoint endpoint =
                    new Endpoint(
                            policyResolver(PolicyDecision.ALLOW),
                            new Handler(tenantId -> Mono.just(METADATA)),
                            new OpenTelemetryRequestTelemetry(
                                    telemetry.openTelemetry(), () -> java.time.Instant.EPOCH));
            WebFilter requestContextFilter = context.getBean(WebFilter.class);
            RequestContextPropagation propagation =
                    context.getBean(RequestContextPropagation.class);

            WebTestClient.bindToRouterFunction(endpoint)
                    .webFilter(
                            (exchange, chain) -> {
                                propagation.attach(exchange, TENANT_REQUEST);
                                return requestContextFilter.filter(exchange, chain);
                            })
                    .build()
                    .get()
                    .uri(Endpoint.PATH)
                    .header("X-Correlation-Id", CORRELATION_ID)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals("X-Correlation-Id", CORRELATION_ID);

            ILoggingEvent logEvent =
                    appender.list.stream()
                            .filter(
                                    event ->
                                            event.getFormattedMessage()
                                                    .equals(
                                                            "Conformance reference slice completed"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(CORRELATION_ID, logEvent.getMDCPropertyMap().get("correlationId"));
            assertEquals(
                    CORRELATION_ID,
                    telemetry
                            .finishedSpans()
                            .getFirst()
                            .getAttributes()
                            .get(AttributeKey.stringKey("correlationId")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void inMemorySubstrateCapturesSliceSpanMetricAndLogWithoutCollector() {
        try (ObservabilityTestFixture telemetry = ObservabilityTestFixture.create(Endpoint.class)) {
            Endpoint endpoint =
                    new Endpoint(
                            policyResolver(PolicyDecision.ALLOW),
                            new Handler(tenantId -> Mono.just(METADATA)),
                            new OpenTelemetryRequestTelemetry(
                                    telemetry.openTelemetry(), () -> java.time.Instant.EPOCH));
            telemetry.meter().counterBuilder("reference_slice_invocation_total").build().add(1);
            clientWithRequestContext(endpoint)
                    .get()
                    .uri(Endpoint.PATH)
                    .exchange()
                    .expectStatus()
                    .isOk();

            assertEquals(Endpoint.SPAN_NAME, telemetry.finishedSpans().getFirst().getName());
            assertEquals(
                    "reference_slice_invocation_total",
                    telemetry.finishedMetrics().getFirst().getName());
            assertEquals(
                    "Conformance reference slice completed",
                    telemetry.logEvents().getFirst().getFormattedMessage());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackages = "org.meldtech.platform.shared.infra.web",
            useDefaultFilters = false,
            includeFilters = {
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern =
                                "org\\.meldtech\\.platform\\.shared\\.infra\\.web\\.RequestContextWebFilter"),
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern =
                                "org\\.meldtech\\.platform\\.shared\\.infra\\.web\\."
                                        + "ReactorContextPropagationConfiguration")
            })
    static class ContextPropagationTestConfiguration {

        @Bean
        RequestContextPropagation requestContextPropagation() {
            return new RequestContextPropagation();
        }

        @Bean
        CorrelationIdGenerator correlationIdGenerator() {
            return () -> CorrelationId.parse(CORRELATION_ID);
        }
    }

    private static WebTestClient clientWithRequestContext(Endpoint endpoint) {
        return WebTestClient.bindToRouterFunction(endpoint)
                .webFilter(
                        (exchange, chain) ->
                                chain.filter(exchange)
                                        .contextWrite(
                                                context ->
                                                        context.put(
                                                                ActorContext.class,
                                                                TENANT_REQUEST)))
                .webFilter(
                        (exchange, chain) ->
                                chain.filter(exchange)
                                        .onErrorResume(
                                                org.springframework.security.access
                                                        .AccessDeniedException.class,
                                                failure -> {
                                                    exchange.getResponse()
                                                            .setStatusCode(HttpStatus.FORBIDDEN);
                                                    return exchange.getResponse().setComplete();
                                                }))
                .build();
    }

    private static PolicyResolver policyResolver(PolicyDecision decision) {
        return new PolicyResolver() {
            @Override
            public <R> Mono<PolicyDecision> evaluate(
                    String routeId, ActorContext actor, R request) {
                return Mono.just(decision);
            }
        };
    }

    private static RequestTelemetry passthroughTelemetry() {
        return new RequestTelemetry() {
            @Override
            public <T> Publisher<T> observe(RequestMetadata metadata, Publisher<T> request) {
                return request;
            }
        };
    }

    private static Queries unusedQueries() {
        return tenantId -> Mono.error(new AssertionError("handler must not be called"));
    }
}
