package org.meldtech.platform.platform.infra.session;

import java.time.Instant;
import java.util.Optional;
import org.meldtech.platform.platform.api.SessionWindowQuery;
import org.meldtech.platform.platform.api.SessionWindowReason;
import org.meldtech.platform.platform.api.SessionWindowResult;
import org.meldtech.platform.platform.api.SessionWindowState;
import reactor.core.publisher.Mono;

final class UnknownSessionWindowQuery implements SessionWindowQuery {

    @Override
    public Mono<SessionWindowResult> query(String targetEnvironment, Instant decisionTime) {
        return Mono.just(
                new SessionWindowResult(
                        SessionWindowState.UNKNOWN,
                        SessionWindowReason.SOURCE_NOT_CONFIGURED,
                        "default-unknown-adapter",
                        decisionTime,
                        Optional.empty()));
    }
}
