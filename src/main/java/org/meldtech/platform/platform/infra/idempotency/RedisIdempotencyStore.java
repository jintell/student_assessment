package org.meldtech.platform.platform.infra.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.meldtech.platform.shared.kernel.idempotency.IdempotencyKey;
import org.meldtech.platform.shared.kernel.idempotency.IdempotencyScope;
import org.meldtech.platform.shared.kernel.idempotency.IdempotencyStore;
import org.meldtech.platform.shared.kernel.idempotency.RequestFingerprint;
import org.meldtech.platform.shared.kernel.idempotency.ReservationOutcome;
import org.meldtech.platform.shared.kernel.idempotency.ReservationToken;
import org.meldtech.platform.shared.kernel.idempotency.StoredResponse;
import org.reactivestreams.Publisher;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public final class RedisIdempotencyStore implements IdempotencyStore {

    static final Duration RETENTION = Duration.ofHours(24);
    private static final String KEY_PREFIX = "idempotency:v1:";
    private static final String FIELD_SEPARATOR = "|";

    private final ReactiveStringRedisTemplate redis;
    private final ReactiveValueOperations<String, String> values;
    private final ObjectMapper objectMapper;

    public RedisIdempotencyStore(ReactiveStringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.values = redis.opsForValue();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public Publisher<ReservationOutcome> reserveOrReplay(
            IdempotencyScope scope, IdempotencyKey key, RequestFingerprint request) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(request, "request");

        String redisKey = redisKey(scope, key);
        String owner = UUID.randomUUID().toString();
        String pending = pending(request, owner);
        return values.setIfAbsent(redisKey, pending, RETENTION)
                .flatMap(
                        reserved ->
                                reserved
                                        ? Mono.just(
                                                new ReservationOutcome.Reserved(
                                                        new ReservationToken(
                                                                redisKey
                                                                        + FIELD_SEPARATOR
                                                                        + owner)))
                                        : replayOrUnavailable(redisKey, request))
                .switchIfEmpty(Mono.just(ReservationOutcome.Unavailable.INSTANCE))
                .onErrorReturn(ReservationOutcome.Unavailable.INSTANCE);
    }

    @Override
    public Publisher<Void> complete(ReservationToken reservation, StoredResponse response) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(response, "response");
        TokenParts token = TokenParts.parse(reservation);
        return Mono.zip(values.get(token.redisKey()), redis.getExpire(token.redisKey()))
                .flatMap(
                        existing -> {
                            String current = existing.getT1();
                            Duration remaining = existing.getT2();
                            if (!isOwnedPending(current, token.owner())
                                    || remaining.isNegative()
                                    || remaining.isZero()) {
                                return Mono.empty();
                            }
                            String fingerprint = current.split("\\|", 3)[1];
                            return values.set(
                                            token.redisKey(),
                                            completed(fingerprint, response),
                                            remaining)
                                    .then();
                        })
                .onErrorResume(ignored -> Mono.empty());
    }

    private Mono<ReservationOutcome> replayOrUnavailable(
            String redisKey, RequestFingerprint request) {
        return values.get(redisKey)
                .map(value -> decodeOutcome(value, request))
                .defaultIfEmpty(ReservationOutcome.Unavailable.INSTANCE);
    }

    private ReservationOutcome decodeOutcome(String value, RequestFingerprint request) {
        String[] parts = value.split("\\|", 3);
        if (parts.length != 3 || !parts[1].equals(request.hex()) || !parts[0].equals("C")) {
            return ReservationOutcome.Unavailable.INSTANCE;
        }
        try {
            byte[] json = Base64.getUrlDecoder().decode(parts[2]);
            ReplayEnvelope envelope = objectMapper.readValue(json, ReplayEnvelope.class);
            return new ReservationOutcome.Replay(envelope.toStoredResponse());
        } catch (IllegalArgumentException | JacksonException exception) {
            return ReservationOutcome.Unavailable.INSTANCE;
        }
    }

    private String completed(String fingerprint, StoredResponse response) {
        try {
            ReplayEnvelope envelope = ReplayEnvelope.from(response);
            String encoded =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(objectMapper.writeValueAsBytes(envelope));
            return "C" + FIELD_SEPARATOR + fingerprint + FIELD_SEPARATOR + encoded;
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize replay response", exception);
        }
    }

    private static boolean isOwnedPending(String value, String owner) {
        String[] parts = value.split("\\|", 3);
        return parts.length == 3 && parts[0].equals("P") && parts[2].equals(owner);
    }

    private static String pending(RequestFingerprint request, String owner) {
        return "P" + FIELD_SEPARATOR + request.hex() + FIELD_SEPARATOR + owner;
    }

    private static String redisKey(IdempotencyScope scope, IdempotencyKey key) {
        String tenant = scope.tenantId().map(Object::toString).orElse("platform");
        String canonical =
                scope.routeId()
                        + '\n'
                        + tenant
                        + '\n'
                        + scope.platformActorId().value()
                        + '\n'
                        + key.value();
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK does not provide SHA-256", impossible);
        }
    }

    private record ReplayEnvelope(
            int status, Map<String, String> headers, String contentType, String body) {

        static ReplayEnvelope from(StoredResponse response) {
            return new ReplayEnvelope(
                    response.status(),
                    response.headers(),
                    response.contentType(),
                    Base64.getEncoder().encodeToString(response.body()));
        }

        StoredResponse toStoredResponse() {
            return new StoredResponse(
                    status, headers, contentType, Base64.getDecoder().decode(body));
        }
    }

    private record TokenParts(String redisKey, String owner) {

        static TokenParts parse(ReservationToken token) {
            int separator = token.value().lastIndexOf(FIELD_SEPARATOR);
            if (separator < 1 || separator == token.value().length() - 1) {
                throw new IllegalArgumentException("Invalid reservation token");
            }
            return new TokenParts(
                    token.value().substring(0, separator), token.value().substring(separator + 1));
        }
    }
}
