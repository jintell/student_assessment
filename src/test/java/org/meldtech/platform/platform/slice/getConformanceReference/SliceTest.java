package org.meldtech.platform.platform.slice.getConformanceReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.api.PolicyDecision;
import org.meldtech.platform.shared.api.PolicyResolver;
import org.meldtech.platform.shared.api.RequestCarrier;
import org.meldtech.platform.shared.api.RequestTenantId;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SliceTest {

    private static final RequestTenantId TENANT_ID =
            new RequestTenantId(UUID.fromString("ad25adad-f989-4a62-9754-3a600e5bf347"));
    private static final RequestCarrier TENANT_REQUEST =
            new RequestCarrier(
                    "request-123", Optional.of(TENANT_ID), Optional.empty(), "127.0.0.1");
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
                new Endpoint(policyResolver(PolicyDecision.ALLOW), new Handler(unusedQueries()));

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
                new Endpoint(policyResolver(PolicyDecision.DENY), new Handler(unusedQueries()));

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
                        new Handler(tenantId -> Mono.just(METADATA)));

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
