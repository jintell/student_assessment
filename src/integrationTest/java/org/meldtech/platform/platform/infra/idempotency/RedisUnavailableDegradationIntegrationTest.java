package org.meldtech.platform.platform.infra.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.RedisTestContainer;
import org.meldtech.platform.shared.api.IdempotencyMechanism;
import org.meldtech.platform.shared.api.IdempotencyPolicy;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.meldtech.platform.shared.api.RouteDescriptor;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.error.ProblemCodeDefinition;
import org.meldtech.platform.shared.kernel.error.ProblemContext;
import org.meldtech.platform.shared.kernel.error.ProblemDetailDocument;
import org.meldtech.platform.shared.kernel.error.ProblemDetailMapper;
import org.meldtech.platform.shared.kernel.error.ProblemDetailMetrics;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.WebFilterChain;
import org.testcontainers.containers.GenericContainer;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class RedisUnavailableDegradationIntegrationTest {

    private static final CorrelationId CORRELATION_ID =
            CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV");
    private static final ActorContext ACTOR =
            ActorContext.tenantWorkforce(
                    new ActorId("staff-7"),
                    TenantId.parse("018f3f1e-7b2a-7cc5-98c4-2c11e17c4698"),
                    CORRELATION_ID,
                    SourceIp.parse("192.0.2.10"));
    private static LettuceConnectionFactory connectionFactory;
    private static RedisIdempotencyStore unavailableStore;

    @BeforeAll
    static void stopRedisBeforeConnecting() {
        GenericContainer<?> stoppedRedis = RedisTestContainer.newInstance();
        stoppedRedis.start();
        String host = stoppedRedis.getHost();
        int port = stoppedRedis.getMappedPort(6379);
        stoppedRedis.stop();

        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(host, port);
        LettuceClientConfiguration client =
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(250))
                        .shutdownTimeout(Duration.ZERO)
                        .build();
        connectionFactory = new LettuceConnectionFactory(server, client);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        unavailableStore =
                new RedisIdempotencyStore(
                        new ReactiveStringRedisTemplate(connectionFactory), new ObjectMapper());
    }

    @AfterAll
    static void closeConnectionFactory() {
        connectionFactory.destroy();
    }

    @Test
    void unavailableRedisReturnsDuplicateRequestProblemWithoutInvokingTheTransition() {
        IdempotencyRequestFilter filter = filter();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("/operations")
                                .header(
                                        IdempotencyRequestFilter.IDEMPOTENCY_KEY_HEADER,
                                        "request-42")
                                .body("{}"));
        AtomicBoolean sideEffect = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        StepVerifier.create(
                        filter.filter(exchange, sideEffectChain(sideEffect))
                                .contextWrite(context -> context.put(ActorContext.class, ACTOR)))
                .expectErrorSatisfies(failure::set)
                .verify(Duration.ofSeconds(5));

        IdempotencyUnavailableException unavailable =
                assertInstanceOf(
                        IdempotencyUnavailableException.class,
                        java.util.Objects.requireNonNull(failure.get()));
        assertFalse(sideEffect.get());
        ProblemDetailDocument problem =
                mapper().map(
                                unavailable,
                                new ProblemContext(URI.create("/operations"), CORRELATION_ID));
        assertEquals("CBT-PLAT-IDEMPOTENCY-UNAVAILABLE", problem.code());
        assertEquals(409, problem.status());
        assertEquals("Duplicate request protection unavailable", problem.title());
        assertEquals("The request cannot be safely repeated at this time.", problem.detail());
    }

    @Test
    void candidateDurableRouteDoesNotDependOnRedisAvailability() {
        IdempotencyRequestFilter filter = filter();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("/candidate/answers").body("{}"));
        AtomicBoolean candidatePathInvoked = new AtomicBoolean();

        StepVerifier.create(
                        filter.filter(exchange, sideEffectChain(candidatePathInvoked))
                                .contextWrite(context -> context.put(ActorContext.class, ACTOR)))
                .verifyComplete();

        assertTrue(candidatePathInvoked.get());
    }

    private static IdempotencyRequestFilter filter() {
        return new IdempotencyRequestFilter(
                new IdempotencyRouteRegistry(
                        List.of(new RedisHeaderRoute(), new CandidateAnswerRoute())),
                unavailableStore,
                new IdempotencyMetrics(new SimpleMeterRegistry()),
                new StoredResponseReplayer());
    }

    private static WebFilterChain sideEffectChain(AtomicBoolean invoked) {
        return ignored -> {
            invoked.set(true);
            return Mono.empty();
        };
    }

    private static ProblemDetailMapper mapper() {
        ProblemCodeDefinition unavailable =
                new ProblemCodeDefinition(
                        URI.create("https://errors.meld-tech.com/problems/idempotency-unavailable"),
                        "Duplicate request protection unavailable",
                        409,
                        "The request cannot be safely repeated at this time.",
                        Map.of());
        return new ProblemDetailMapper(
                Map.of("CBT-PLAT-IDEMPOTENCY-UNAVAILABLE", unavailable),
                Map.of(IdempotencyUnavailableException.class, "CBT-PLAT-IDEMPOTENCY-UNAVAILABLE"),
                ProblemDetailMetrics.NOOP,
                () -> CORRELATION_ID);
    }

    private abstract static class StubRoute implements PolicyProtectedRoute {

        @Override
        public Mono<HandlerFunction<ServerResponse>> route(ServerRequest request) {
            return Mono.empty();
        }

        @Override
        public void accept(RouterFunctions.Visitor visitor) {}
    }

    @IdempotencyPolicy(createsDurableRecord = false, mechanism = IdempotencyMechanism.REDIS_HEADER)
    private static final class RedisHeaderRoute extends StubRoute {

        @Override
        public RouteDescriptor descriptor() {
            return RouteDescriptor.tenant(
                    "platform.operation", HttpMethod.POST, "/operations", "platform");
        }
    }

    @IdempotencyPolicy(
            createsDurableRecord = true,
            mechanism = IdempotencyMechanism.POSTGRES_UNIQUE,
            databaseProtection = "uq_answer_attempt_operation")
    private static final class CandidateAnswerRoute extends StubRoute {

        @Override
        public RouteDescriptor descriptor() {
            return RouteDescriptor.tenant(
                    "delivery.acceptAnswer", HttpMethod.POST, "/candidate/answers", "delivery");
        }
    }
}
