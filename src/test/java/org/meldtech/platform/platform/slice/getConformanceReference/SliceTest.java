package org.meldtech.platform.platform.slice.getConformanceReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.test.simple.SimpleTracer;
import io.micrometer.tracing.test.simple.TracerAssert;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.api.PolicyDecision;
import org.meldtech.platform.shared.api.PolicyResolver;
import org.meldtech.platform.shared.api.RequestActor;
import org.meldtech.platform.shared.api.RequestActorType;
import org.meldtech.platform.shared.api.RequestCarrier;
import org.meldtech.platform.shared.api.RequestTenantId;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SliceTest {

    private static final RequestTenantId TENANT_ID =
            new RequestTenantId(UUID.fromString("ad25adad-f989-4a62-9754-3a600e5bf347"));
    private static final RequestCarrier TENANT_REQUEST =
            new RequestCarrier(
                    "request-123",
                    Optional.of(TENANT_ID),
                    Optional.of(new RequestActor(RequestActorType.WORKFORCE_USER, "operator-123")),
                    "127.0.0.1");
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
        RequestCarrier carrier =
                new RequestCarrier("request-123", Optional.empty(), Optional.empty(), "127.0.0.1");
        Handler handler = new Handler(tenantId -> Mono.just(METADATA));

        StepVerifier.create(handler.handle(carrier, Request.INSTANCE))
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
                        ObservationRegistry.NOOP);

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
    void endpointReturnsANonDisclosingPolicyDenial() {
        Endpoint endpoint =
                new Endpoint(
                        policyResolver(PolicyDecision.DENY),
                        new Handler(unusedQueries()),
                        ObservationRegistry.NOOP);

        clientWithRequestContext(endpoint)
                .get()
                .uri(Endpoint.PATH)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .value(
                        body -> {
                            assertEquals(Set.of("status", "code", "correlationId"), body.keySet());
                            assertEquals(403, body.get("status"));
                            assertEquals("ACCESS_DENIED", body.get("code"));
                            assertEquals(TENANT_REQUEST.correlationId(), body.get("correlationId"));
                        });
    }

    @Test
    void endpointReturnsMetadataWhenPolicyAllows() {
        Endpoint endpoint =
                new Endpoint(
                        policyResolver(PolicyDecision.ALLOW),
                        new Handler(tenantId -> Mono.just(METADATA)),
                        ObservationRegistry.NOOP);

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
        SimpleTracer tracer = new SimpleTracer();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationHandler(new DefaultTracingObservationHandler(tracer));
        Endpoint endpoint =
                new Endpoint(
                        policyResolver(PolicyDecision.ALLOW),
                        new Handler(tenantId -> Mono.just(METADATA)),
                        registry);

        clientWithRequestContext(endpoint)
                .get()
                .uri(Endpoint.PATH)
                .exchange()
                .expectStatus()
                .isOk();

        TracerAssert.assertThat(tracer)
                .onlySpan()
                .hasNameEqualTo(Endpoint.SPAN_NAME)
                .hasTag("module", "platform")
                .hasTag("slice", "getConformanceReference")
                .hasTag("audience", "operator")
                .hasTag("actorType", RequestActorType.WORKFORCE_USER.name())
                .hasTag("operation", "READ")
                .hasTag("correlationId", TENANT_REQUEST.correlationId())
                .hasTag("tenantId", TENANT_ID.value().toString());
    }

    @Test
    void propagatesCorrelationIdFromFilterToLogAndSpan() {
        SimpleTracer tracer = new SimpleTracer();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationHandler(new DefaultTracingObservationHandler(tracer));
        Endpoint endpoint =
                new Endpoint(
                        policyResolver(PolicyDecision.DENY),
                        new Handler(unusedQueries()),
                        registry);
        Logger logger = (Logger) LoggerFactory.getLogger(Endpoint.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(ContextPropagationTestConfiguration.class)) {
            WebFilter requestContextFilter = context.getBean(WebFilter.class);

            WebTestClient.bindToRouterFunction(endpoint)
                    .webFilter(requestContextFilter)
                    .build()
                    .get()
                    .uri(Endpoint.PATH)
                    .header("X-Correlation-Id", "request-123")
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectHeader()
                    .valueEquals("X-Correlation-Id", "request-123");

            ILoggingEvent logEvent =
                    appender.list.stream()
                            .filter(
                                    event ->
                                            event.getFormattedMessage()
                                                    .equals(
                                                            "Conformance reference slice completed"))
                            .findFirst()
                            .orElseThrow();
            assertEquals("request-123", logEvent.getMDCPropertyMap().get("correlationId"));
            TracerAssert.assertThat(tracer)
                    .onlySpan()
                    .hasNameEqualTo(Endpoint.SPAN_NAME)
                    .hasTag("correlationId", "request-123");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
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
    static class ContextPropagationTestConfiguration {}

    private static WebTestClient clientWithRequestContext(Endpoint endpoint) {
        return WebTestClient.bindToRouterFunction(endpoint)
                .webFilter(
                        (exchange, chain) ->
                                chain.filter(exchange)
                                        .contextWrite(
                                                context ->
                                                        context.put(
                                                                RequestCarrier.class,
                                                                TENANT_REQUEST)))
                .build();
    }

    private static PolicyResolver policyResolver(PolicyDecision decision) {
        return new PolicyResolver() {
            @Override
            public <R> Mono<PolicyDecision> evaluate(
                    String routeId, RequestCarrier carrier, R request) {
                return Mono.just(decision);
            }
        };
    }

    private static Queries unusedQueries() {
        return tenantId -> Mono.error(new AssertionError("handler must not be called"));
    }
}
