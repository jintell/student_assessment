package org.meldtech.platform.platform.infra.idempotency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.RedisTestContainer;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.idempotency.IdempotencyKey;
import org.meldtech.platform.shared.kernel.idempotency.IdempotencyScope;
import org.meldtech.platform.shared.kernel.idempotency.RequestFingerprint;
import org.meldtech.platform.shared.kernel.idempotency.ReservationOutcome;
import org.meldtech.platform.shared.kernel.idempotency.StoredResponse;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class RedisIdempotencyStoreIntegrationTest {

    private static final GenericContainer<?> REDIS = RedisTestContainer.instance();
    private static LettuceConnectionFactory connectionFactory;
    private static ReactiveStringRedisTemplate redis;
    private static RedisIdempotencyStore store;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connectionFactory =
                new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new ReactiveStringRedisTemplate(connectionFactory);
        store = new RedisIdempotencyStore(redis, new ObjectMapper());
    }

    @AfterAll
    static void closeConnectionFactory() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void clearRedis() throws Exception {
        assertEquals(0, REDIS.execInContainer("redis-cli", "FLUSHALL").getExitCode());
    }

    @Test
    void reservesReplaysIdenticallyAndAllowsANewReservationAfterExpiry() {
        ReservationOutcome.Reserved reserved =
                assertInstanceOf(
                        ReservationOutcome.Reserved.class,
                        Objects.requireNonNull(
                                Mono.from(store.reserveOrReplay(scope(), key(), fingerprint()))
                                        .block()));
        String redisKey = redisKey(reserved);
        Duration initialTtl = Objects.requireNonNull(redis.getExpire(redisKey).block());
        assertTrue(initialTtl.compareTo(Duration.ofHours(24)) <= 0);
        assertTrue(initialTtl.compareTo(Duration.ofHours(23).plusMinutes(59)) > 0);
        StoredResponse response =
                new StoredResponse(
                        201,
                        Map.of("Location", "/operations/42"),
                        "application/json",
                        "{\"operationId\":\"42\"}".getBytes(StandardCharsets.UTF_8));

        Mono.from(store.complete(reserved.token(), response)).block();

        ReservationOutcome.Replay replay =
                assertInstanceOf(
                        ReservationOutcome.Replay.class,
                        Objects.requireNonNull(
                                Mono.from(store.reserveOrReplay(scope(), key(), fingerprint()))
                                        .block()));
        assertEquals(response.status(), replay.response().status());
        assertEquals(response.headers(), replay.response().headers());
        assertEquals(response.contentType(), replay.response().contentType());
        assertArrayEquals(response.body(), replay.response().body());

        StepVerifier.create(
                        redis.expire(redisKey, Duration.ofMillis(50))
                                .flatMap(
                                        changed ->
                                                changed
                                                        ? Mono.delay(Duration.ofMillis(100))
                                                                .then(
                                                                        Mono.from(
                                                                                store
                                                                                        .reserveOrReplay(
                                                                                                scope(),
                                                                                                key(),
                                                                                                fingerprint())))
                                                        : Mono.error(
                                                                new AssertionError(
                                                                        "Redis key expiry was not changed"))))
                .assertNext(outcome -> assertInstanceOf(ReservationOutcome.Reserved.class, outcome))
                .verifyComplete();
    }

    private static String redisKey(ReservationOutcome.Reserved reserved) {
        String token = reserved.token().value();
        return token.substring(0, token.lastIndexOf('|'));
    }

    private static IdempotencyScope scope() {
        return new IdempotencyScope("platform.operation", Optional.empty(), new ActorId("staff-7"));
    }

    private static IdempotencyKey key() {
        return new IdempotencyKey("operation-42");
    }

    private static RequestFingerprint fingerprint() {
        return RequestFingerprint.sha256("POST\nplatform.operation\napplication/json\n{}");
    }
}
