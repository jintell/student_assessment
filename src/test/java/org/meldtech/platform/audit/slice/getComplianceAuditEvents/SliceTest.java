package org.meldtech.platform.audit.slice.getComplianceAuditEvents;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.api.PolicyDecision;
import org.meldtech.platform.shared.api.PolicyResolver;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class SliceTest {

    private static final TenantId TENANT = TenantId.parse("01991a95-df27-7000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final ActorContext ACTOR =
            ActorContext.tenantWorkforce(
                    new ActorId("officer-42"),
                    TENANT,
                    CorrelationId.parse("01K74Q5Y7B0000000000000000"),
                    SourceIp.parse("127.0.0.1"));

    @Test
    void endpointFailsClosedWithoutActorContextOrCapability() {
        Handler handler = handler(unusedQueries(), new AtomicReference<>());
        Endpoint allowedEndpoint =
                new Endpoint(resolver(new Policy(allowingCapability())), handler);

        WebTestClient.bindToRouterFunction(allowedEndpoint)
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

        Endpoint deniedEndpoint =
                new Endpoint(
                        resolver(new Policy((actorId, tenantId, capability) -> Mono.just(false))),
                        handler);
        client(deniedEndpoint)
                .get()
                .uri(Endpoint.PATH)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .isEmpty();
    }

    @Test
    void policyUsesTheAuthoritativeActorTenantCapabilityTuple() {
        AtomicBoolean consulted = new AtomicBoolean();
        Policy policy =
                new Policy(
                        (actorId, tenantId, capability) -> {
                            assertThat(actorId).isEqualTo(ACTOR.actorId());
                            assertThat(tenantId).isEqualTo(TENANT);
                            assertThat(capability).isEqualTo(Policy.REQUIRED_CAPABILITY);
                            consulted.set(true);
                            return Mono.just(true);
                        });

        StepVerifier.create(policy.evaluate(ACTOR, request(2)))
                .expectNext(PolicyDecision.ALLOW)
                .verifyComplete();
        assertThat(consulted).isTrue();
    }

    @Test
    void returnsATenantScopedPageAndAuditsThePrivilegedRead() {
        AtomicReference<AuditEvent> emitted = new AtomicReference<>();
        Queries queries =
                (tenantId, request, asOf, after, limit) -> {
                    assertThat(tenantId).isEqualTo(TENANT);
                    assertThat(asOf).isEqualTo(NOW);
                    assertThat(after).isEmpty();
                    assertThat(limit).isEqualTo(3);
                    return Mono.just(List.of(item(3), item(2), item(1)));
                };
        Endpoint endpoint =
                new Endpoint(resolver(new Policy(allowingCapability())), handler(queries, emitted));

        client(endpoint)
                .get()
                .uri(builder -> builder.path(Endpoint.PATH).queryParam("pageSize", 2).build())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.APPLICATION_JSON)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.items.length()")
                .isEqualTo(2)
                .jsonPath("$.hasMore")
                .isEqualTo(true)
                .jsonPath("$.nextCursor")
                .value(value -> assertThat((String) value).isNotBlank());

        assertThat(emitted.get())
                .isNotNull()
                .extracting(AuditEvent::eventType)
                .isEqualTo("audit.COMPLIANCE_AUDIT_EVENTS_READ.v1");
    }

    @Test
    void continuationCursorPreservesTenantFilterAndSnapshot() {
        HmacAuditCursorCodec cursors = new HmacAuditCursorCodec(new byte[32], new ObjectMapper());
        Request original = request(2);
        String cursor =
                cursors.encode(
                        new ComplianceCursor(
                                ComplianceCursor.CURRENT_SCHEMA_VERSION,
                                TENANT.toString(),
                                original.filterFingerprint(),
                                NOW,
                                NOW.minusSeconds(30),
                                "GENERAL_AUDIT_EVENT",
                                0,
                                2));
        AtomicReference<ComplianceCursor> received = new AtomicReference<>();
        Queries queries =
                (tenantId, request, asOf, after, limit) -> {
                    received.set(after.orElseThrow());
                    return Mono.just(List.of(item(1)));
                };
        Handler handler =
                new Handler(
                        queries,
                        cursors,
                        request -> true,
                        Clock.fixed(NOW.plusSeconds(300), ZoneOffset.UTC),
                        (event, actor, occurredAt) -> Mono.empty());
        Request continuation =
                new Request(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(cursor),
                        2);

        StepVerifier.create(handler.handle(ACTOR, continuation))
                .assertNext(
                        response -> {
                            assertThat(response.items()).hasSize(1);
                            assertThat(response.hasMore()).isFalse();
                        })
                .verifyComplete();
        assertThat(received.get()).isNotNull().extracting(ComplianceCursor::asOf).isEqualTo(NOW);
    }

    private static Handler handler(Queries queries, AtomicReference<AuditEvent> emittedAuditEvent) {
        return new Handler(
                queries,
                new HmacAuditCursorCodec(new byte[32], new ObjectMapper()),
                request -> true,
                Clock.fixed(NOW, ZoneOffset.UTC),
                (event, actor, occurredAt) -> {
                    emittedAuditEvent.set(event);
                    return Mono.empty();
                });
    }

    private static AuditComplianceCapabilityView allowingCapability() {
        return (actorId, tenantId, capability) -> Mono.just(true);
    }

    private static PolicyResolver resolver(Policy policy) {
        return new PolicyResolver() {
            @Override
            public <R> Mono<PolicyDecision> evaluate(
                    String routeId, ActorContext actor, R request) {
                assertThat(routeId).isEqualTo(Endpoint.ROUTE_ID);
                return policy.evaluate(actor, (Request) request);
            }
        };
    }

    private static WebTestClient client(Endpoint endpoint) {
        return WebTestClient.bindToRouterFunction(endpoint)
                .webFilter(
                        (exchange, chain) ->
                                chain.filter(exchange)
                                        .contextWrite(
                                                context -> context.put(ActorContext.class, ACTOR)))
                .webFilter(
                        (exchange, chain) ->
                                chain.filter(exchange)
                                        .onErrorResume(
                                                AccessDeniedException.class,
                                                failure -> {
                                                    exchange.getResponse()
                                                            .setStatusCode(HttpStatus.FORBIDDEN);
                                                    return exchange.getResponse().setComplete();
                                                }))
                .build();
    }

    private static Request request(int pageSize) {
        return new Request(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                pageSize);
    }

    private static Queries.AuditEventItem item(long sequence) {
        return new Queries.AuditEventItem(
                new UUID(0, sequence),
                "audit.TEST.v1",
                "audit.test",
                "entity-" + sequence,
                "WORKFORCE_USER",
                "officer-42",
                NOW.minusSeconds(sequence),
                ACTOR.correlationId().toString(),
                "GENERAL_AUDIT_EVENT",
                0,
                sequence);
    }

    private static Queries unusedQueries() {
        return (tenantId, request, asOf, after, limit) ->
                Mono.error(new AssertionError("handler must not be called"));
    }
}
