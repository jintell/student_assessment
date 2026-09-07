package org.meldtech.platform.conformance.fixtures.r8;

import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

public class Handler {

    @Transactional
    public Mono<Void> mutate() {
        return Mono.empty();
    }
}
