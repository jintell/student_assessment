package org.meldtech.platform.platform.api;

import java.time.Instant;
import reactor.core.publisher.Mono;

public interface SessionWindowQuery {

    Mono<SessionWindowResult> query(String targetEnvironment, Instant decisionTime);
}
