package org.meldtech.platform.conformance.fixtures.actor.freetext;

import org.meldtech.platform.shared.kernel.context.SystemActor;

public final class FreeTextSystemActor {

    public SystemActor parse(String actorName) {
        return SystemActor.valueOf(actorName);
    }
}
