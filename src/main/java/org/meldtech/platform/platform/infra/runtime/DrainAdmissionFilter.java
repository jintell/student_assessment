package org.meldtech.platform.platform.infra.runtime;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
final class DrainAdmissionFilter implements WebFilter {
    private final RuntimeDrainState state;

    DrainAdmissionFilter(RuntimeDrainState state) {
        this.state = state;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.defer(
                () -> {
                    if (state.isDraining() && !ManagementRequest.isProbeOrScrape(exchange)) {
                        exchange.getResponse().getHeaders().set("Retry-After", "1");
                        return Mono.error(new RuntimeUnavailableException());
                    }
                    return chain.filter(exchange);
                });
    }
}
