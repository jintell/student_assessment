package org.meldtech.platform.shared.api;

import org.meldtech.platform.shared.kernel.context.ActorContext;
import reactor.util.context.Context;

/** Publishes actor and logging context for reactive work across module boundaries. */
public interface ActorContextPropagation {

    /** Installs the actor's context, removing inherited tenant metadata for platform actors. */
    Context write(Context context, ActorContext actor);
}
