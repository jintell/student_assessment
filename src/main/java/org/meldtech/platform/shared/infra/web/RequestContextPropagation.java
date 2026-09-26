package org.meldtech.platform.shared.infra.web;

import java.util.Objects;
import java.util.Optional;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

@Component
public final class RequestContextPropagation {

    public static final String CORRELATION_ID_KEY = "correlationId";
    public static final String ACTOR_TYPE_KEY = "actorType";
    public static final String ACTOR_ID_KEY = "actorId";
    public static final String TENANT_ID_KEY = "tenantId";
    private static final String EXCHANGE_ATTRIBUTE = ActorContext.class.getName();

    public void attach(ServerWebExchange exchange, ActorContext actor) {
        Objects.requireNonNull(exchange, "exchange")
                .getAttributes()
                .put(EXCHANGE_ATTRIBUTE, Objects.requireNonNull(actor, "actor"));
    }

    Optional<ActorContext> attached(ServerWebExchange exchange) {
        return Optional.ofNullable(exchange.getAttribute(EXCHANGE_ATTRIBUTE));
    }

    public Context write(Context context, ActorContext actor) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(actor, "actor");
        Context result =
                context.put(ActorContext.class, actor)
                        .put(CORRELATION_ID_KEY, actor.correlationId().toString())
                        .put(ACTOR_TYPE_KEY, actor.actorType().name())
                        .put(ACTOR_ID_KEY, actor.actorId().toString());
        return actor.tenantId()
                .map(tenantId -> result.put(TENANT_ID_KEY, tenantId.toString()))
                .orElseGet(
                        () -> result.hasKey(TENANT_ID_KEY) ? result.delete(TENANT_ID_KEY) : result);
    }

    public ActorContext require(ContextView context) {
        return Objects.requireNonNull(context, "context").get(ActorContext.class);
    }
}
