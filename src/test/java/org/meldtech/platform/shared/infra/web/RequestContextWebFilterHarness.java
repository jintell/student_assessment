package org.meldtech.platform.shared.infra.web;

import java.util.Objects;
import java.util.function.Supplier;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

public final class RequestContextWebFilterHarness {

    private RequestContextWebFilterHarness() {}

    public static Mono<String> filter(
            String correlationId, ActorContext actor, Supplier<Mono<Void>> requestPath) {
        RequestContextPropagation propagation = new RequestContextPropagation();
        RequestContextWebFilter filter =
                new RequestContextWebFilter(actor::correlationId, propagation);
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/observability/propagation")
                                .header(
                                        RequestContextWebFilter.CORRELATION_ID_HEADER,
                                        correlationId));
        propagation.attach(exchange, actor);

        return filter.filter(exchange, ignored -> Objects.requireNonNull(requestPath.get()))
                .then(
                        Mono.fromSupplier(
                                () ->
                                        exchange.getResponse()
                                                .getHeaders()
                                                .getFirst(
                                                        RequestContextWebFilter
                                                                .CORRELATION_ID_HEADER)));
    }
}
