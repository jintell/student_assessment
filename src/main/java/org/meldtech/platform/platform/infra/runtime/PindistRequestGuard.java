package org.meldtech.platform.platform.infra.runtime;

import org.meldtech.platform.platform.api.RoleScoped;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

@Component
@RoleScoped("pindist")
@Order(Ordered.HIGHEST_PRECEDENCE)
final class PindistRequestGuard implements WebFilter {
    private static final PathPattern RESERVED =
            new PathPatternParser().parse(PindistRouteValidator.PATH);
    private final boolean delivered;

    PindistRequestGuard() {
        this(FeatureDeliveryStatus.load().contains(PindistRouteValidator.FEATURE));
    }

    PindistRequestGuard(boolean delivered) {
        this.delivered = delivered;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        String raw = request.getURI().getRawPath();
        if (!delivered
                || !HttpMethod.GET.equals(request.getMethod())
                || raw.contains("%")
                || raw.contains(";")
                || raw.contains("\\")
                || raw.contains("//")
                || !RESERVED.matches(request.getPath().pathWithinApplication())) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
