package org.meldtech.platform.platform.infra.persistence;

import org.meldtech.platform.shared.api.PlatformOperation;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/** Narrow entry points into the connection factory's closed database-security policy. */
public final class DatabaseSecurityScopes {

    private DatabaseSecurityScopes() {}

    public static <T> Mono<T> withOutboxRelay(ActorContext actor, Publisher<T> work) {
        return SecurityContextInitializer.withPlatformScope(
                AssumableDatabaseRole.OUTBOX_RELAY, PlatformOperation.OUTBOX_RELAY, actor, work);
    }
}
