package org.meldtech.platform.platform.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RollbackPolicyTest {

    private static final String DIGEST = "sha256:" + "a".repeat(64);

    @Test
    void contractIsNeverRolledBackAndNamesTheForwardFix() {
        var evidenceCalled = new AtomicBoolean();
        var policy =
                new RollbackPolicy(
                        (release, checksum, digest) -> {
                            evidenceCalled.set(true);
                            return Mono.just(true);
                        });

        StepVerifier.create(policy.evaluate(request(ReleaseClassification.CONTRACT)))
                .assertNext(
                        decision -> {
                            assertFalse(decision.permitted());
                            assertEquals(
                                    RollbackPolicy.CONTRACT_ROLLBACK_FORBIDDEN, decision.code());
                            assertEquals(30, decision.exitCode());
                            assertTrue(decision.message().contains("forward-fix migration"));
                        })
                .verifyComplete();
        assertFalse(evidenceCalled.get());
    }

    @Test
    void expandAllowsOnlyCodeRollbackWithGreenCompatibilityEvidence() {
        var policy = new RollbackPolicy((release, checksum, digest) -> Mono.just(true));

        StepVerifier.create(policy.evaluate(request(ReleaseClassification.EXPAND)))
                .assertNext(
                        decision -> {
                            assertTrue(decision.permitted());
                            assertEquals(DIGEST, decision.targetImageDigest().orElseThrow());
                            assertTrue(decision.message().contains("do not roll back schema"));
                        })
                .verifyComplete();
    }

    private static RollbackRequest request(ReleaseClassification classification) {
        return new RollbackRequest(
                "2026.09.0",
                classification,
                DIGEST,
                "sha256:" + "b".repeat(64),
                "production",
                "deployer@example.test");
    }
}
