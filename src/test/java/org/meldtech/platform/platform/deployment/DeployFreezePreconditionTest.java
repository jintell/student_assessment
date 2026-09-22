package org.meldtech.platform.platform.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.SessionWindowReason;
import org.meldtech.platform.platform.api.SessionWindowResult;
import org.meldtech.platform.platform.api.SessionWindowState;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DeployFreezePreconditionTest {

    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");

    @Test
    void noneProceeds() {
        var check = check(result(SessionWindowState.NONE, SessionWindowReason.AUTHORITATIVE_NONE));

        StepVerifier.create(check.evaluate("production", NOW))
                .assertNext(
                        decision -> {
                            assertTrue(decision.permitted());
                            assertEquals(
                                    DeployFreezeDecision.PERMITTED_EXIT_CODE, decision.exitCode());
                            assertEquals("NONE", decision.refusalReason());
                        })
                .verifyComplete();
    }

    @Test
    void openRefuses() {
        StepVerifier.create(
                        check(
                                        result(
                                                SessionWindowState.OPEN,
                                                SessionWindowReason.AUTHORITATIVE_OPEN))
                                .evaluate("production", NOW))
                .assertNext(
                        decision -> {
                            assertFalse(decision.permitted());
                            assertEquals(
                                    DeployFreezeDecision.SESSION_OPEN_EXIT_CODE,
                                    decision.exitCode());
                            assertEquals("SESSION_OPEN", decision.refusalReason());
                        })
                .verifyComplete();
    }

    @Test
    void unknownRefuses() {
        StepVerifier.create(
                        check(
                                        result(
                                                SessionWindowState.UNKNOWN,
                                                SessionWindowReason.SOURCE_NOT_CONFIGURED))
                                .evaluate("production", NOW))
                .assertNext(
                        decision -> {
                            assertFalse(decision.permitted());
                            assertEquals(
                                    DeployFreezeDecision.SOURCE_UNKNOWN_EXIT_CODE,
                                    decision.exitCode());
                            assertEquals("SOURCE_UNKNOWN", decision.refusalReason());
                        })
                .verifyComplete();
    }

    @Test
    void errorsAndEmptyResultsFailClosedAsUnknown() {
        var failed =
                new DeployFreezePrecondition(
                        (environment, time) -> Mono.error(new IllegalStateException("offline")),
                        Duration.ofSeconds(1));
        var empty =
                new DeployFreezePrecondition(
                        (environment, time) -> Mono.empty(), Duration.ofSeconds(1));

        StepVerifier.create(failed.evaluate("production", NOW))
                .assertNext(
                        decision ->
                                assertEquals(
                                        DeployFreezeDecision.SOURCE_UNKNOWN_EXIT_CODE,
                                        decision.exitCode()))
                .verifyComplete();
        StepVerifier.create(empty.evaluate("production", NOW))
                .assertNext(
                        decision ->
                                assertEquals(
                                        DeployFreezeDecision.SOURCE_UNKNOWN_EXIT_CODE,
                                        decision.exitCode()))
                .verifyComplete();
    }

    private static DeployFreezePrecondition check(SessionWindowResult result) {
        return new DeployFreezePrecondition(
                (environment, time) -> Mono.just(result), Duration.ofSeconds(1));
    }

    private static SessionWindowResult result(
            SessionWindowState state, SessionWindowReason reason) {
        return new SessionWindowResult(state, reason, "test", NOW, Optional.empty());
    }
}
