package org.meldtech.platform.platform.infra.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.meldtech.platform.shared.kernel.observability.RequestTelemetry;
import org.springframework.stereotype.Component;

@Component
final class FoundationMetrics implements DatabaseQueryTelemetry {

    private final MeterRegistry registry;
    private final Map<Workload, AtomicInteger> pendingByWorkload;
    private final ConcurrentMap<HttpKey, Timer> httpTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<Workload, Timer> poolAcquireTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<QueryKey, Timer> queryTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<RedisKey, Timer> redisTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<RedisErrorKey, Counter> redisErrors = new ConcurrentHashMap<>();

    FoundationMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        EnumMap<Workload, AtomicInteger> pending = new EnumMap<>(Workload.class);
        for (Workload workload : Workload.values()) {
            AtomicInteger value = new AtomicInteger();
            pending.put(workload, value);
            Gauge.builder("db_pool_pending", value, AtomicInteger::doubleValue)
                    .tag("workload", tag(workload))
                    .register(registry);
        }
        pendingByWorkload = Map.copyOf(pending);
    }

    void recordHttpRequest(
            RequestTelemetry.RequestMetadata metadata,
            HttpMethod method,
            int statusCode,
            Duration duration) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(method, "method");
        requireDuration(duration);
        HttpStatusClass statusClass = HttpStatusClass.from(statusCode);
        HttpOutcome outcome = HttpOutcome.from(statusClass);
        HttpKey key =
                new HttpKey(
                        metadata.routeClass(), metadata.audience(), method, statusClass, outcome);
        safelyRecord(
                () -> httpTimers.computeIfAbsent(key, this::registerHttpTimer).record(duration));
    }

    void recordPoolAcquire(Workload workload, Duration duration) {
        Objects.requireNonNull(workload, "workload");
        requireDuration(duration);
        safelyRecord(
                () ->
                        poolAcquireTimers
                                .computeIfAbsent(
                                        workload,
                                        value ->
                                                Timer.builder("db_pool_acquire_duration")
                                                        .tag("workload", tag(value))
                                                        .register(registry))
                                .record(duration));
    }

    void updatePoolPending(Workload workload, int pending) {
        Objects.requireNonNull(workload, "workload");
        if (pending < 0) {
            throw new IllegalArgumentException("pending connection count must be non-negative");
        }
        Objects.requireNonNull(pendingByWorkload.get(workload)).set(pending);
    }

    @Override
    public void record(RequestTelemetry.RequestMetadata metadata, Duration duration) {
        Objects.requireNonNull(metadata, "metadata");
        requireDuration(duration);
        QueryKey key = new QueryKey(metadata.slice(), metadata.operation());
        safelyRecord(
                () -> queryTimers.computeIfAbsent(key, this::registerQueryTimer).record(duration));
    }

    void recordRedisCommand(RedisCommand command, RedisOutcome outcome, Duration duration) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(outcome, "outcome");
        requireDuration(duration);
        RedisKey key = new RedisKey(command, outcome);
        safelyRecord(
                () -> redisTimers.computeIfAbsent(key, this::registerRedisTimer).record(duration));
    }

    void recordRedisError(RedisCommand command, RedisErrorReason reason) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(reason, "reason");
        RedisErrorKey key = new RedisErrorKey(command, reason);
        safelyRecord(
                () ->
                        redisErrors
                                .computeIfAbsent(key, this::registerRedisErrorCounter)
                                .increment());
    }

    private Timer registerHttpTimer(HttpKey key) {
        return Timer.builder("http_server_requests")
                .tag("routeClass", tag(key.routeClass()))
                .tag("audience", tag(key.audience()))
                .tag("method", key.method().name())
                .tag("statusClass", key.statusClass().tag())
                .tag("outcome", tag(key.outcome()))
                .register(registry);
    }

    private Timer registerQueryTimer(QueryKey key) {
        return Timer.builder("db_query_duration")
                .tag("slice", key.slice())
                .tag("operation", tag(key.operation()))
                .register(registry);
    }

    private Timer registerRedisTimer(RedisKey key) {
        return Timer.builder("redis_command_duration")
                .tag("command", tag(key.command()))
                .tag("outcome", tag(key.outcome()))
                .register(registry);
    }

    private Counter registerRedisErrorCounter(RedisErrorKey key) {
        return Counter.builder("redis_errors_total")
                .tag("command", tag(key.command()))
                .tag("reason", tag(key.reason()))
                .register(registry);
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static void requireDuration(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("metric duration must be non-negative");
        }
    }

    private static void safelyRecord(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // Telemetry cannot change request behavior.
        }
    }

    enum Workload {
        API,
        WORKER,
        PINDIST
    }

    enum HttpMethod {
        GET,
        HEAD,
        POST,
        PUT,
        PATCH,
        DELETE,
        OPTIONS
    }

    enum HttpStatusClass {
        INFORMATIONAL("1xx"),
        SUCCESS("2xx"),
        REDIRECTION("3xx"),
        CLIENT_ERROR("4xx"),
        SERVER_ERROR("5xx");

        private final String tag;

        HttpStatusClass(String tag) {
            this.tag = tag;
        }

        static HttpStatusClass from(int statusCode) {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("HTTP status code must be between 100 and 599");
            }
            return values()[statusCode / 100 - 1];
        }

        String tag() {
            return tag;
        }
    }

    enum HttpOutcome {
        SUCCESS,
        CLIENT_ERROR,
        SERVER_ERROR;

        static HttpOutcome from(HttpStatusClass statusClass) {
            return switch (statusClass) {
                case CLIENT_ERROR -> CLIENT_ERROR;
                case SERVER_ERROR -> SERVER_ERROR;
                default -> SUCCESS;
            };
        }
    }

    enum RedisCommand {
        GET,
        SET,
        DELETE,
        EVAL
    }

    enum RedisOutcome {
        SUCCESS,
        MISS,
        ERROR
    }

    enum RedisErrorReason {
        TIMEOUT,
        UNAVAILABLE,
        REJECTED,
        UNKNOWN
    }

    private record HttpKey(
            RequestTelemetry.RouteClass routeClass,
            RequestTelemetry.Audience audience,
            HttpMethod method,
            HttpStatusClass statusClass,
            HttpOutcome outcome) {}

    private record QueryKey(String slice, RequestTelemetry.Operation operation) {}

    private record RedisKey(RedisCommand command, RedisOutcome outcome) {}

    private record RedisErrorKey(RedisCommand command, RedisErrorReason reason) {}
}
