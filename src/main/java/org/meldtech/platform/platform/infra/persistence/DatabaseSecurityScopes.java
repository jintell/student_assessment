package org.meldtech.platform.platform.infra.persistence;

import io.r2dbc.spi.Connection;
import org.meldtech.platform.platform.api.SweepMetadata;
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

    public static <T> Mono<T> inSweepCoordinationTransaction(
            Connection connection, Publisher<T> work) {
        return requireCoordinationActor(
                SecurityContextInitializer.inInternalPlatformTransaction(
                        connection, PlatformOperation.SWEEP_REGISTRY_READ, work));
    }

    /** Begins a lock-only transaction whose retained lease must be rolled back by its owner. */
    public static Mono<Void> beginSweepCoordinationTransaction(Connection connection) {
        return requireCoordinationActor(
                SecurityContextInitializer.beginInternalPlatformTransaction(
                        connection, PlatformOperation.SWEEP_REGISTRY_READ));
    }

    private static <T> Mono<T> requireCoordinationActor(Publisher<T> work) {
        return Mono.deferContextual(
                context -> {
                    // Bootstrap reads have no actor; a tick must retain its validated declaration
                    // and actor.
                    if (context.hasKey(ActorContext.class) || context.hasKey(SweepMetadata.class)) {
                        DefaultSweepTransactions.requireSweepActor(context);
                    }
                    return Mono.from(work);
                });
    }
}
