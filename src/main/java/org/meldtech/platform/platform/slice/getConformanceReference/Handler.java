package org.meldtech.platform.platform.slice.getConformanceReference;

import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

@Component
class Handler {

    private final Queries queries;

    Handler(Queries queries) {
        this.queries = queries;
    }

    @Transactional(readOnly = true)
    public Mono<Response> handle(ActorContext actor, Request request) {
        return Mono.justOrEmpty(actor.tenantId())
                .switchIfEmpty(Mono.error(new IllegalStateException("tenant context is required")))
                .flatMap(queries::load)
                .map(Handler::toResponse);
    }

    private static Response toResponse(Queries.ConformanceMetadata metadata) {
        return new Response(
                metadata.applicationVersion(),
                metadata.architectureVersion(),
                metadata.architectureCommit(),
                metadata.contextModuleCount(),
                metadata.platformModuleCount(),
                metadata.rules());
    }
}
