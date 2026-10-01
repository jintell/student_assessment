package org.meldtech.platform.platform.infra.outbox;

import java.util.Objects;
import org.meldtech.platform.platform.infra.persistence.DatabaseSecurityScopes;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

final class RelaySecurityContext {

    <T> Mono<T> install(ActorContext actor, Publisher<T> work) {
        return DatabaseSecurityScopes.withOutboxRelay(
                Objects.requireNonNull(actor, "actor"), Objects.requireNonNull(work, "work"));
    }
}
