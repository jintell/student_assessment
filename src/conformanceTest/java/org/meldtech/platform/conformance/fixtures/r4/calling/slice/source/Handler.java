package org.meldtech.platform.conformance.fixtures.r4.calling.slice.source;

import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

public class Handler {

    private final org.meldtech.platform.conformance.fixtures.r4.calling.slice.target.Handler
            target =
                    new org.meldtech.platform.conformance.fixtures.r4.calling.slice.target
                            .Handler();

    @Transactional
    public Mono<Void> handle() {
        return target.handle();
    }
}
