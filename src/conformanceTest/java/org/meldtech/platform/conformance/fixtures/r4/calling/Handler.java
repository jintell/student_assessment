package org.meldtech.platform.conformance.fixtures.r4.calling;

import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

public class Handler {

    private final org.meldtech.platform.conformance.fixtures.r4.target.Handler target =
            new org.meldtech.platform.conformance.fixtures.r4.target.Handler();

    @Transactional
    public Mono<Void> handle() {
        return target.handle();
    }
}
