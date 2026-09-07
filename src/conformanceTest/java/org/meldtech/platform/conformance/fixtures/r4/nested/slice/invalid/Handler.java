package org.meldtech.platform.conformance.fixtures.r4.nested.slice.invalid;

import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

public class Handler {

    @Transactional
    public Mono<Void> handle() {
        return nested();
    }

    @Transactional
    public Mono<Void> nested() {
        return Mono.empty();
    }
}
