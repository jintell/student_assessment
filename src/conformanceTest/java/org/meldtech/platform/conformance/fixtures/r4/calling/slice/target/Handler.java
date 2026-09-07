package org.meldtech.platform.conformance.fixtures.r4.calling.slice.target;

import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

public class Handler {

    @Transactional
    public Mono<Void> handle() {
        return Mono.empty();
    }
}
