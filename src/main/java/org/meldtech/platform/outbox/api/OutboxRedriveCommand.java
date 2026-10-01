package org.meldtech.platform.outbox.api;

import reactor.core.publisher.Mono;

/** Application command consumed by the FEAT-OPS-002 operator surface. */
public interface OutboxRedriveCommand {

    Mono<RedriveResult> redriveFailed(FailedOutboxRedrive request);

    Mono<RedriveResult> redriveDeadLetter(DeadLetterRedrive request);
}
