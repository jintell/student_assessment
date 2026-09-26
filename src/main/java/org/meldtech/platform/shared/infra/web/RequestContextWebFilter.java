package org.meldtech.platform.shared.infra.web;

import java.util.List;
import java.util.Optional;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.CorrelationIdGenerator;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class RequestContextWebFilter implements WebFilter {

    static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final CorrelationIdGenerator correlationIds;
    private final RequestContextPropagation propagation;

    RequestContextWebFilter(
            CorrelationIdGenerator correlationIds, RequestContextPropagation propagation) {
        this.correlationIds = correlationIds;
        this.propagation = propagation;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = resolveCorrelationId(exchange).toString();
        exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);
        Optional<ActorContext> actor = propagation.attached(exchange);
        actor.ifPresent(value -> requireMatchingRequestMetadata(value, correlationId));
        return chain.filter(exchange)
                .contextWrite(
                        context -> {
                            reactor.util.context.Context seeded =
                                    context.put(
                                            RequestContextPropagation.CORRELATION_ID_KEY,
                                            correlationId);
                            return actor.map(value -> propagation.write(seeded, value))
                                    .orElse(seeded);
                        });
    }

    private CorrelationId resolveCorrelationId(ServerWebExchange exchange) {
        List<String> supplied = exchange.getRequest().getHeaders().get(CORRELATION_ID_HEADER);
        if (supplied != null
                && supplied.size() == 1
                && CorrelationId.isValid(supplied.getFirst())) {
            return CorrelationId.parse(supplied.getFirst());
        }
        return correlationIds.generate();
    }

    private static void requireMatchingRequestMetadata(ActorContext actor, String correlationId) {
        if (!actor.correlationId().toString().equals(correlationId)) {
            throw new IllegalArgumentException(
                    "Attached ActorContext must carry the validated request correlation identifier");
        }
    }
}
