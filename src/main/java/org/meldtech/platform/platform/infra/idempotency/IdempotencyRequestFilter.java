package org.meldtech.platform.platform.infra.idempotency;

import java.util.Base64;
import java.util.Optional;
import org.meldtech.platform.shared.api.RouteDescriptor;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.idempotency.IdempotencyKey;
import org.meldtech.platform.shared.kernel.idempotency.IdempotencyScope;
import org.meldtech.platform.shared.kernel.idempotency.IdempotencyStore;
import org.meldtech.platform.shared.kernel.idempotency.RequestFingerprint;
import org.meldtech.platform.shared.kernel.idempotency.ReservationOutcome;
import org.meldtech.platform.shared.kernel.idempotency.ReservationToken;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
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
    private final IdempotencyMetrics metrics;
    private final StoredResponseReplayer responseReplayer;

    IdempotencyRequestFilter(
            IdempotencyRouteRegistry routes,
            IdempotencyStore store,
            IdempotencyMetrics metrics,
            StoredResponseReplayer responseReplayer) {
        this.routes = routes;
        this.store = store;
        this.metrics = metrics;
        this.responseReplayer = responseReplayer;
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
                            metrics.record(outcome);
                            if (outcome instanceof ReservationOutcome.Reserved reserved) {
                                return executeReserved(exchange, chain, body, reserved.token());
                            }
                            if (outcome instanceof ReservationOutcome.Replay replay) {
                                return responseReplayer.replay(exchange, replay.response());
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
        IdempotencyResponseCapture response =
                new IdempotencyResponseCapture(exchange.getResponse(), reservation, store);
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
}
