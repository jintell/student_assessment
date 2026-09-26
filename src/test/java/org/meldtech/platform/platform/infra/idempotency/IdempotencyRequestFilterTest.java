package org.meldtech.platform.platform.infra.idempotency;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.api.IdempotencyMechanism;
import org.meldtech.platform.shared.api.IdempotencyPolicy;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.meldtech.platform.shared.api.RouteDescriptor;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.idempotency.IdempotencyKey;
import org.meldtech.platform.shared.kernel.idempotency.IdempotencyScope;
import org.meldtech.platform.shared.kernel.idempotency.IdempotencyStore;
import org.meldtech.platform.shared.kernel.idempotency.RequestFingerprint;
import org.meldtech.platform.shared.kernel.idempotency.ReservationOutcome;
import org.meldtech.platform.shared.kernel.idempotency.ReservationToken;
import org.meldtech.platform.shared.kernel.idempotency.StoredResponse;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.reactivestreams.Publisher;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class IdempotencyRequestFilterTest {

    private static final ActorContext ACTOR =
            ActorContext.tenantWorkforce(
                    new ActorId("staff-7"),
                    TenantId.parse("018f3f1e-7b2a-7cc5-98c4-2c11e17c4698"),
                    CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV"),
                    SourceIp.parse("192.0.2.10"));

    @Test
    void unavailableStorePreventsTheTransition() {
        PolicyProtectedRoute route = new RedisHeaderRoute();
        IdempotencyRequestFilter filter =
                new IdempotencyRequestFilter(
                        new IdempotencyRouteRegistry(List.of(route)), new UnavailableStore());
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("/operations")
                                .header(
                                        IdempotencyRequestFilter.IDEMPOTENCY_KEY_HEADER,
                                        "request-42")
                                .body("{}"));
        AtomicBoolean invoked = new AtomicBoolean();

        StepVerifier.create(
                        filter.filter(
                                        exchange,
                                        ignored -> {
                                            invoked.set(true);
                                            return Mono.empty();
                                        })
                                .contextWrite(context -> context.put(ActorContext.class, ACTOR)))
                .expectErrorSatisfies(
                        failure -> assertInstanceOf(IdempotencyUnavailableException.class, failure))
                .verify();
        assertFalse(invoked.get());
    }

    @Test
    void reservedResponseIsPersistedForReplay() {
        RecordingStore store = new RecordingStore();
        IdempotencyRequestFilter filter =
                new IdempotencyRequestFilter(
                        new IdempotencyRouteRegistry(List.of(new RedisHeaderRoute())), store);
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("/operations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header(
                                        IdempotencyRequestFilter.IDEMPOTENCY_KEY_HEADER,
                                        "request-42")
                                .body("{\"command\":\"start\"}"));
        byte[] responseBody = "{\"status\":\"accepted\"}".getBytes(UTF_8);

        StepVerifier.create(
                        filter.filter(
                                        exchange,
                                        decorated -> {
                                            decorated
                                                    .getResponse()
                                                    .setStatusCode(HttpStatus.CREATED);
                                            decorated
                                                    .getResponse()
                                                    .getHeaders()
                                                    .setContentType(MediaType.APPLICATION_JSON);
                                            decorated
                                                    .getResponse()
                                                    .getHeaders()
                                                    .setLocation(URI.create("/operations/42"));
                                            return decorated
                                                    .getResponse()
                                                    .writeWith(
                                                            Mono.just(
                                                                    decorated
                                                                            .getResponse()
                                                                            .bufferFactory()
                                                                            .wrap(responseBody)));
                                        })
                                .contextWrite(context -> context.put(ActorContext.class, ACTOR)))
                .verifyComplete();

        StoredResponse persisted = store.completed.orElseThrow();
        assertEquals(HttpStatus.CREATED.value(), persisted.status());
        assertEquals(MediaType.APPLICATION_JSON_VALUE, persisted.contentType());
        assertEquals("/operations/42", persisted.headers().get("Location"));
        assertArrayEquals(responseBody, persisted.body());
    }

    @IdempotencyPolicy(createsDurableRecord = false, mechanism = IdempotencyMechanism.REDIS_HEADER)
    private static final class RedisHeaderRoute implements PolicyProtectedRoute {

        private final RouterFunction<ServerResponse> delegate =
                RouterFunctions.route()
                        .POST("/operations", request -> ServerResponse.noContent().build())
                        .build();

        @Override
        public RouteDescriptor descriptor() {
            return RouteDescriptor.tenant(
                    "platform.operation", HttpMethod.POST, "/operations", "platform");
        }

        @Override
        public Mono<HandlerFunction<ServerResponse>> route(ServerRequest request) {
            return delegate.route(request);
        }

        @Override
        public void accept(RouterFunctions.Visitor visitor) {
            delegate.accept(visitor);
        }
    }

    private static final class UnavailableStore implements IdempotencyStore {

        @Override
        public Publisher<ReservationOutcome> reserveOrReplay(
                IdempotencyScope scope, IdempotencyKey key, RequestFingerprint request) {
            return Mono.just(ReservationOutcome.Unavailable.INSTANCE);
        }

        @Override
        public Publisher<Void> complete(ReservationToken reservation, StoredResponse response) {
            return Mono.empty();
        }
    }

    private static final class RecordingStore implements IdempotencyStore {

        private Optional<StoredResponse> completed = Optional.empty();

        @Override
        public Publisher<ReservationOutcome> reserveOrReplay(
                IdempotencyScope scope, IdempotencyKey key, RequestFingerprint request) {
            return Mono.just(new ReservationOutcome.Reserved(new ReservationToken("reservation")));
        }

        @Override
        public Publisher<Void> complete(ReservationToken reservation, StoredResponse response) {
            completed = Optional.of(response);
            return Mono.empty();
        }
    }
}
