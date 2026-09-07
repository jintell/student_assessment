package org.meldtech.platform.conformance.fixtures.r4.target;

import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

public class Handler {

    @Transactional
    public Mono<Void> handle() {
        return Mono.empty();
    }
}
