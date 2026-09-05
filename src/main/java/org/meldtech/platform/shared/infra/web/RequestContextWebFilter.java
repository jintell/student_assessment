package org.meldtech.platform.shared.infra.web;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.meldtech.platform.shared.api.RequestCarrier;
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
    private static final Pattern VALID_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = resolveCorrelationId(exchange);
        RequestCarrier carrier =
                new RequestCarrier(
                        correlationId,
                        Optional.empty(),
                        Optional.empty(),
                        resolveSourceIp(exchange));

        exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);
        return chain.filter(exchange)
                .contextWrite(
                        context ->
                                context.put(RequestCarrier.class, carrier)
                                        .put(MdcCorrelationIdAccessor.KEY, correlationId));
    }

    private static String resolveCorrelationId(ServerWebExchange exchange) {
        String supplied = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);
        if (supplied != null && VALID_CORRELATION_ID.matcher(supplied).matches()) {
            return supplied;
        }
        return UUID.randomUUID().toString();
    }

    private static String resolveSourceIp(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        return remoteAddress == null ? "unknown" : remoteAddress.getAddress().getHostAddress();
    }
}
