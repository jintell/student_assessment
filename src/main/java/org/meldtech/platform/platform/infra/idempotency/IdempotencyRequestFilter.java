package org.meldtech.platform.platform.infra.idempotency;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.meldtech.platform.shared.api.RouteDescriptor;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.idempotency.IdempotencyKey;
import org.meldtech.platform.shared.kernel.idempotency.IdempotencyScope;
import org.meldtech.platform.shared.kernel.idempotency.IdempotencyStore;
import org.meldtech.platform.shared.kernel.idempotency.RequestFingerprint;
import org.meldtech.platform.shared.kernel.idempotency.ReservationOutcome;
import org.meldtech.platform.shared.kernel.idempotency.ReservationToken;
import org.meldtech.platform.shared.kernel.idempotency.StoredResponse;
import org.reactivestreams.Publisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
final class IdempotencyRequestFilter implements WebFilter {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final IdempotencyRouteRegistry routes;
    private final IdempotencyStore store;

    IdempotencyRequestFilter(IdempotencyRouteRegistry routes, IdempotencyStore store) {
        this.routes = routes;
        this.store = store;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Optional<RouteDescriptor> route =
                routes.redisHeaderRoute(
                        exchange.getRequest().getMethod(), exchange.getRequest().getPath().value());
        if (route.isEmpty()) {
            return chain.filter(exchange);
        }
        return Mono.deferContextual(
                context -> {
                    Optional<ActorContext> actor = context.getOrEmpty(ActorContext.class);
                    String suppliedKey =
                            exchange.getRequest().getHeaders().getFirst(IDEMPOTENCY_KEY_HEADER);
                    if (actor.isEmpty() || suppliedKey == null) {
                        return Mono.error(new IdempotencyUnavailableException());
                    }
                    IdempotencyKey key = new IdempotencyKey(suppliedKey);
                    return DataBufferUtils.join(exchange.getRequest().getBody())
                            .map(IdempotencyRequestFilter::readAndRelease)
                            .defaultIfEmpty(new byte[0])
                            .flatMap(
                                    body ->
                                            reserve(
                                                    exchange,
                                                    chain,
                                                    route.orElseThrow(),
                                                    actor.orElseThrow(),
                                                    key,
                                                    body));
                });
    }

    private Mono<Void> reserve(
            ServerWebExchange exchange,
            WebFilterChain chain,
            RouteDescriptor route,
            ActorContext actor,
            IdempotencyKey key,
            byte[] body) {
        IdempotencyScope scope =
                new IdempotencyScope(route.routeId(), actor.tenantId(), actor.actorId());
        RequestFingerprint fingerprint = fingerprint(exchange, route, body);
        return Mono.from(store.reserveOrReplay(scope, key, fingerprint))
                .flatMap(
                        outcome -> {
                            if (outcome instanceof ReservationOutcome.Reserved reserved) {
                                return executeReserved(exchange, chain, body, reserved.token());
                            }
                            if (outcome instanceof ReservationOutcome.Replay replay) {
                                return replay(exchange, replay.response());
                            }
                            return Mono.error(new IdempotencyUnavailableException());
                        });
    }

    private Mono<Void> executeReserved(
            ServerWebExchange exchange,
            WebFilterChain chain,
            byte[] requestBody,
            ReservationToken reservation) {
        ServerHttpRequestDecorator request =
                new ServerHttpRequestDecorator(exchange.getRequest()) {
                    @Override
                    public Flux<DataBuffer> getBody() {
                        return Flux.just(exchange.getResponse().bufferFactory().wrap(requestBody));
                    }
                };
        CapturingResponse response = new CapturingResponse(exchange, reservation, store);
        ServerWebExchange decorated =
                new ServerWebExchangeDecorator(exchange) {
                    @Override
                    public ServerHttpRequest getRequest() {
                        return request;
                    }

                    @Override
                    public ServerHttpResponse getResponse() {
                        return response;
                    }
                };
        return chain.filter(decorated);
    }

    private static Mono<Void> replay(ServerWebExchange exchange, StoredResponse response) {
        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(response.status()));
        exchange.getResponse().getHeaders().set(HttpHeaders.CONTENT_TYPE, response.contentType());
        response.headers().forEach(exchange.getResponse().getHeaders()::set);
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(response.body())));
    }

    private static RequestFingerprint fingerprint(
            ServerWebExchange exchange, RouteDescriptor route, byte[] body) {
        String contentType =
                Optional.ofNullable(exchange.getRequest().getHeaders().getContentType())
                        .map(Object::toString)
                        .orElse("");
        String canonical =
                exchange.getRequest().getMethod().name()
                        + '\n'
                        + route.routeId()
                        + '\n'
                        + contentType
                        + '\n'
                        + Base64.getEncoder().encodeToString(body);
        return RequestFingerprint.sha256(canonical);
    }

    private static byte[] readAndRelease(DataBuffer buffer) {
        byte[] body = new byte[buffer.readableByteCount()];
        buffer.read(body);
        DataBufferUtils.release(buffer);
        return body;
    }

    private static final class CapturingResponse extends ServerHttpResponseDecorator {

        private final ReservationToken reservation;
        private final IdempotencyStore store;
        private final AtomicBoolean completed = new AtomicBoolean();

        private CapturingResponse(
                ServerWebExchange exchange, ReservationToken reservation, IdempotencyStore store) {
            super(exchange.getResponse());
            this.reservation = reservation;
            this.store = store;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return DataBufferUtils.join(Flux.from(body))
                    .map(IdempotencyRequestFilter::readAndRelease)
                    .defaultIfEmpty(new byte[0])
                    .flatMap(this::completeAndWrite);
        }

        @Override
        public Mono<Void> setComplete() {
            return completeAndWrite(new byte[0]);
        }

        private Mono<Void> completeAndWrite(byte[] body) {
            if (!completed.compareAndSet(false, true)) {
                return Mono.empty();
            }
            HttpStatusCode status = getStatusCode();
            int statusValue = status == null ? 200 : status.value();
            String contentType =
                    Optional.ofNullable(getHeaders().getContentType())
                            .map(Object::toString)
                            .orElse("application/octet-stream");
            StoredResponse response =
                    new StoredResponse(statusValue, replayHeaders(getHeaders()), contentType, body);
            return Mono.from(store.complete(reservation, response))
                    .then(
                            body.length == 0
                                    ? super.setComplete()
                                    : super.writeWith(Mono.just(bufferFactory().wrap(body))));
        }

        private static Map<String, String> replayHeaders(HttpHeaders headers) {
            Map<String, String> replay = new LinkedHashMap<>();
            copy(headers, replay, HttpHeaders.LOCATION);
            copy(headers, replay, HttpHeaders.RETRY_AFTER);
            return Map.copyOf(replay);
        }

        private static void copy(HttpHeaders source, Map<String, String> target, String name) {
            String value = source.getFirst(name);
            if (value != null) {
                target.put(name, value);
            }
        }
    }
}
