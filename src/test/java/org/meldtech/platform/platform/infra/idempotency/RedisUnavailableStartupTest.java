package org.meldtech.platform.platform.infra.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.api.IdempotencyMechanism;
import org.meldtech.platform.shared.api.IdempotencyPolicy;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.meldtech.platform.shared.api.RouteDescriptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class RedisUnavailableStartupTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class);

    @Test
    void startsAndServesTheCandidatePathWithoutConnectingToRedis() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(RedisIdempotencyStore.class);
                    IdempotencyRequestFilter filter =
                            context.getBean(IdempotencyRequestFilter.class);
                    MockServerWebExchange exchange =
                            MockServerWebExchange.from(
                                    MockServerHttpRequest.post("/candidate/answers").body("{}"));
                    AtomicBoolean candidatePathInvoked = new AtomicBoolean();
                    WebFilterChain chain =
                            ignored -> {
                                candidatePathInvoked.set(true);
                                return Mono.empty();
                            };

                    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

                    assertThat(candidatePathInvoked).isTrue();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {

        @Bean
        LettuceConnectionFactory redisConnectionFactory() {
            RedisStandaloneConfiguration redis = new RedisStandaloneConfiguration("127.0.0.1", 1);
            return new LettuceConnectionFactory(redis);
        }

        @Bean
        ReactiveStringRedisTemplate redisTemplate(LettuceConnectionFactory connectionFactory) {
            return new ReactiveStringRedisTemplate(
                    connectionFactory, RedisSerializationContext.string());
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        RedisIdempotencyStore redisIdempotencyStore(
                ReactiveStringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
            return new RedisIdempotencyStore(redisTemplate, objectMapper);
        }

        @Bean
        CandidateAnswerRoute candidateAnswerRoute() {
            return new CandidateAnswerRoute();
        }

        @Bean
        IdempotencyRouteRegistry routeRegistry(List<PolicyProtectedRoute> routes) {
            return new IdempotencyRouteRegistry(routes);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        IdempotencyMetrics idempotencyMetrics(MeterRegistry meterRegistry) {
            return new IdempotencyMetrics(meterRegistry);
        }

        @Bean
        StoredResponseReplayer storedResponseReplayer() {
            return new StoredResponseReplayer();
        }

        @Bean
        IdempotencyRequestFilter idempotencyRequestFilter(
                IdempotencyRouteRegistry routes,
                RedisIdempotencyStore store,
                IdempotencyMetrics metrics,
                StoredResponseReplayer responseReplayer) {
            return new IdempotencyRequestFilter(routes, store, metrics, responseReplayer);
        }
    }

    private abstract static class StubRoute implements PolicyProtectedRoute {

        @Override
        public Mono<HandlerFunction<ServerResponse>> route(ServerRequest request) {
            return Mono.empty();
        }

        @Override
        public void accept(RouterFunctions.Visitor visitor) {}
    }

    @IdempotencyPolicy(
            createsDurableRecord = true,
            mechanism = IdempotencyMechanism.POSTGRES_UNIQUE,
            databaseProtection = "uq_answer_attempt_operation")
    static final class CandidateAnswerRoute extends StubRoute {

        @Override
        public RouteDescriptor descriptor() {
            return RouteDescriptor.tenant(
                    "delivery.acceptAnswer", HttpMethod.POST, "/candidate/answers", "delivery");
        }
    }
}
