package org.meldtech.platform.platform.infra.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.idempotency.IdempotencyKey;
import org.meldtech.platform.shared.kernel.idempotency.IdempotencyScope;
import org.meldtech.platform.shared.kernel.idempotency.RequestFingerprint;
import org.meldtech.platform.shared.kernel.idempotency.ReservationOutcome;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class RedisIdempotencyStoreTest {

    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ReactiveValueOperations<String, String> values =
            mock(ReactiveValueOperations.class);

    private RedisIdempotencyStore store;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        store = new RedisIdempotencyStore(redis, new ObjectMapper());
    }

    @Test
    void reservesForExactlyTwentyFourHours() {
        when(values.setIfAbsent(anyString(), anyString(), eq(RedisIdempotencyStore.RETENTION)))
                .thenReturn(Mono.just(true));

        StepVerifier.create(store.reserveOrReplay(scope(), key(), fingerprint()))
                .assertNext(outcome -> assertInstanceOf(ReservationOutcome.Reserved.class, outcome))
                .verifyComplete();
        assertEquals(java.time.Duration.ofHours(24), RedisIdempotencyStore.RETENTION);
    }

    @Test
    void normalizesRedisFailureToUnavailable() {
        when(values.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenReturn(Mono.error(new IllegalStateException("redis unavailable")));

        StepVerifier.create(store.reserveOrReplay(scope(), key(), fingerprint()))
                .expectNext(ReservationOutcome.Unavailable.INSTANCE)
                .verifyComplete();
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
