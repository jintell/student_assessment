package org.meldtech.platform.platform.deployment;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.meldtech.platform.platform.api.DeployFreezeRefusalReason;
import org.meldtech.platform.platform.api.DeployFreezeRefusalRecorder;
import org.meldtech.platform.platform.api.SessionWindowQuery;
import org.meldtech.platform.platform.api.SessionWindowReason;
import org.meldtech.platform.platform.api.SessionWindowResult;
import org.meldtech.platform.platform.api.SessionWindowState;
import reactor.core.publisher.Mono;

public final class DeployFreezePrecondition {

    private final SessionWindowQuery sessionWindowQuery;
    private final Duration queryTimeout;
    private final DeployFreezeRefusalRecorder refusalRecorder;

    public DeployFreezePrecondition(SessionWindowQuery sessionWindowQuery, Duration queryTimeout) {
        this(sessionWindowQuery, queryTimeout, ignored -> {});
    }

    public DeployFreezePrecondition(
            SessionWindowQuery sessionWindowQuery,
            Duration queryTimeout,
            DeployFreezeRefusalRecorder refusalRecorder) {
        if (queryTimeout.isZero() || queryTimeout.isNegative()) {
            throw new IllegalArgumentException("Session-window query timeout must be positive");
        }
        this.sessionWindowQuery = sessionWindowQuery;
        this.queryTimeout = queryTimeout;
        this.refusalRecorder = refusalRecorder;
    }

    public Mono<DeployFreezeDecision> evaluate(String targetEnvironment, Instant decisionTime) {
        return sessionWindowQuery
                .query(targetEnvironment, decisionTime)
                .timeout(queryTimeout)
                .map(this::decide)
                .switchIfEmpty(
                        Mono.fromSupplier(
                                        () ->
                                                unknown(
                                                        decisionTime,
                                                        SessionWindowReason.EMPTY_RESPONSE))
                                .map(this::decide))
                .onErrorResume(
                        TimeoutException.class,
                        ignored ->
                                Mono.just(
                                        decide(
                                                unknown(
                                                        decisionTime,
                                                        SessionWindowReason.SOURCE_TIMEOUT))))
                .onErrorResume(
                        ignored ->
                                Mono.just(
                                        decide(
                                                unknown(
                                                        decisionTime,
                                                        SessionWindowReason.SOURCE_UNAVAILABLE))));
    }

    private DeployFreezeDecision decide(SessionWindowResult result) {
        return switch (result.state()) {
            case NONE ->
                    new DeployFreezeDecision(
                            true, DeployFreezeDecision.PERMITTED_EXIT_CODE, "NONE", result);
            case OPEN ->
                    refusal(
                            DeployFreezeRefusalReason.SESSION_OPEN,
                            DeployFreezeDecision.SESSION_OPEN_EXIT_CODE,
                            result);
            case UNKNOWN ->
                    refusal(
                            DeployFreezeRefusalReason.SOURCE_UNKNOWN,
                            DeployFreezeDecision.SOURCE_UNKNOWN_EXIT_CODE,
                            result);
        };
    }

    private DeployFreezeDecision refusal(
            DeployFreezeRefusalReason reason, int exitCode, SessionWindowResult result) {
        refusalRecorder.record(reason);
        return new DeployFreezeDecision(false, exitCode, reason.name(), result);
    }

    private static SessionWindowResult unknown(Instant decisionTime, SessionWindowReason reason) {
        return new SessionWindowResult(
                SessionWindowState.UNKNOWN,
                reason,
                "deploy-freeze-precondition",
                decisionTime,
                Optional.empty());
    }
}
