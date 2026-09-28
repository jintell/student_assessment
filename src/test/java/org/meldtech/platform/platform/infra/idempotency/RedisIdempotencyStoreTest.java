package org.meldtech.platform.platform.infra.idempotency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.idempotency.IdempotencyKey;
import org.meldtech.platform.shared.kernel.idempotency.IdempotencyScope;
import org.meldtech.platform.shared.kernel.idempotency.RequestFingerprint;
import org.meldtech.platform.shared.kernel.idempotency.ReservationOutcome;
import org.meldtech.platform.shared.kernel.idempotency.StoredResponse;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.core.JacksonException;
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

    @Test
    void replayIsPartitionedByTenantAndActor() {
        Set<String> reservedRedisKeys = new HashSet<>();
        when(values.setIfAbsent(anyString(), anyString(), eq(RedisIdempotencyStore.RETENTION)))
                .thenAnswer(
                        invocation ->
                                Mono.just(
                                        reservedRedisKeys.add(
                                                invocation.getArgument(0, String.class))));
        when(values.get(anyString())).thenReturn(Mono.just(completedEntry()));
        IdempotencyScope original = scope("ad25adad-f989-4a62-9754-3a600e5bf347", "staff-7");

        StepVerifier.create(store.reserveOrReplay(original, key(), fingerprint()))
                .assertNext(outcome -> assertInstanceOf(ReservationOutcome.Reserved.class, outcome))
                .verifyComplete();
        StepVerifier.create(store.reserveOrReplay(original, key(), fingerprint()))
                .assertNext(outcome -> assertInstanceOf(ReservationOutcome.Replay.class, outcome))
                .verifyComplete();
        StepVerifier.create(
                        store.reserveOrReplay(
                                scope("1eef9440-775a-44cb-9fd7-a9a37e429352", "staff-7"),
                                key(),
                                fingerprint()))
                .assertNext(outcome -> assertInstanceOf(ReservationOutcome.Reserved.class, outcome))
                .verifyComplete();
        StepVerifier.create(
                        store.reserveOrReplay(
                                scope("ad25adad-f989-4a62-9754-3a600e5bf347", "staff-8"),
                                key(),
                                fingerprint()))
                .assertNext(outcome -> assertInstanceOf(ReservationOutcome.Reserved.class, outcome))
                .verifyComplete();

        assertEquals(3, reservedRedisKeys.size());
    }

    @Test
    void storedEntryContainsOnlyTheReplayableResponseAndPreservesItsTtl() throws JacksonException {
        when(values.setIfAbsent(anyString(), anyString(), eq(RedisIdempotencyStore.RETENTION)))
                .thenReturn(Mono.just(true));
        ReservationOutcome outcome =
                Objects.requireNonNull(
                        Mono.from(store.reserveOrReplay(tenantScope(), key(), fingerprint()))
                                .block());
        ReservationOutcome.Reserved reserved =
                assertInstanceOf(ReservationOutcome.Reserved.class, outcome);
        ArgumentCaptor<String> redisKey = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> pendingValue = ArgumentCaptor.forClass(String.class);
        verify(values)
                .setIfAbsent(
                        redisKey.capture(),
                        pendingValue.capture(),
                        eq(RedisIdempotencyStore.RETENTION));
        Duration remainingTtl = Duration.ofHours(23);
        when(values.get(redisKey.getValue())).thenReturn(Mono.just(pendingValue.getValue()));
        when(redis.getExpire(redisKey.getValue())).thenReturn(Mono.just(remainingTtl));
        ArgumentCaptor<String> completedValue = ArgumentCaptor.forClass(String.class);
        when(values.set(eq(redisKey.getValue()), completedValue.capture(), eq(remainingTtl)))
                .thenReturn(Mono.just(true));
        byte[] responseBody = "{\"candidateName\":\"Ada\"}".getBytes(StandardCharsets.UTF_8);

        Mono.from(
                        store.complete(
                                reserved.token(),
                                new StoredResponse(
                                        201,
                                        Map.of("Location", "/operations/42"),
                                        "application/json",
                                        responseBody)))
                .block();

        String[] storedParts = completedValue.getValue().split("\\|", 3);
        byte[] envelopeBytes = Base64.getUrlDecoder().decode(storedParts[2]);
        tools.jackson.databind.JsonNode envelope = new ObjectMapper().readTree(envelopeBytes);
        assertEquals(Set.of("status", "headers", "contentType", "body"), envelope.propertyNames());
        assertArrayEquals(
                responseBody, Base64.getDecoder().decode(envelope.get("body").asString()));
        assertFalse(
                redisKey.getValue().contains(tenantScope().tenantId().orElseThrow().toString()));
        assertFalse(redisKey.getValue().contains(tenantScope().platformActorId().value()));
        assertFalse(redisKey.getValue().contains(key().value()));
    }

    private static IdempotencyScope scope() {
        return new IdempotencyScope("platform.operation", Optional.empty(), new ActorId("staff-7"));
    }

    private static IdempotencyScope scope(String tenantId, String actorId) {
        return new IdempotencyScope(
                "platform.operation", Optional.of(TenantId.parse(tenantId)), new ActorId(actorId));
    }

    private static IdempotencyScope tenantScope() {
        return scope("ad25adad-f989-4a62-9754-3a600e5bf347", "staff-7");
    }

    private static IdempotencyKey key() {
        return new IdempotencyKey("operation-42");
    }

    private static RequestFingerprint fingerprint() {
        return RequestFingerprint.sha256("POST\nplatform.operation\napplication/json\n{}");
    }

    private static String completedEntry() {
        String envelope =
                "{\"status\":201,\"headers\":{},\"contentType\":\"application/json\",\"body\":\"e30=\"}";
        String encoded =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(envelope.getBytes(StandardCharsets.UTF_8));
        return "C|" + fingerprint().hex() + "|" + encoded;
    }
}
