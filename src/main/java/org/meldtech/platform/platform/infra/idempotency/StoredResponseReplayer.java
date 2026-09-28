package org.meldtech.platform.platform.infra.idempotency;

import org.meldtech.platform.shared.kernel.idempotency.StoredResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
final class StoredResponseReplayer {

    Mono<Void> replay(ServerWebExchange exchange, StoredResponse response) {
        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(response.status()));
        exchange.getResponse().getHeaders().set(HttpHeaders.CONTENT_TYPE, response.contentType());
        response.headers().forEach(exchange.getResponse().getHeaders()::set);
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(response.body())));
    }
}
