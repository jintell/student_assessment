package org.meldtech.platform.platform.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.SessionWindowReason;
import org.meldtech.platform.platform.api.SessionWindowResult;
import org.meldtech.platform.platform.api.SessionWindowState;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class EmergencyOverridePolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");
    private static final String DIGEST = "sha256:" + "a".repeat(64);

    @Test
    void validEvidenceWithTwoNamedApprovalsIsAuditedBeforePermit() {
        var audited = new AtomicReference<EmergencyOverrideAuditEvent>();
        EmergencyOverrideEvidence evidence = evidence("INC-42");
        var policy =
                new EmergencyOverridePolicy(
                        ignored -> Mono.just(evidence),
                        ignored -> Mono.just(true),
                        event -> {
                            audited.set(event);
                            return Mono.empty();
                        });

        StepVerifier.create(policy.authorize(freeze(), request()))
                .assertNext(
                        decision -> {
                            assertTrue(decision.permitted());
                            assertEquals("EMERGENCY_OVERRIDE", decision.reason());
                        })
                .verifyComplete();
        assertEquals("PERMITTED", Objects.requireNonNull(audited.get()).outcome());
        assertEquals(
                List.of("lead@example.test", "ops@example.test"),
                Objects.requireNonNull(audited.get()).approvers());
        assertEquals("INC-42", Objects.requireNonNull(audited.get()).incidentReference());
    }

    @Test
    void missingIncidentReferenceIsRefusedAndAudited() {
        var audited = new AtomicReference<EmergencyOverrideAuditEvent>();
        var policy =
                new EmergencyOverridePolicy(
                        ignored -> Mono.just(evidence("")),
                        ignored -> Mono.just(true),
                        event -> {
                            audited.set(event);
                            return Mono.empty();
                        });

        StepVerifier.create(policy.authorize(freeze(), request()))
                .assertNext(
                        decision -> {
                            assertFalse(decision.permitted());
                            assertEquals("OVERRIDE_INVALID", decision.reason());
                        })
                .verifyComplete();
        assertEquals("OVERRIDE_INVALID", Objects.requireNonNull(audited.get()).outcome());
    }

    @Test
    void onePersonHoldingBothApprovalRolesIsRefusedAndAudited() {
        var audited = new AtomicReference<EmergencyOverrideAuditEvent>();
        var evidence =
                evidence(
                        "INC-42",
                        List.of(
                                approval(
                                        "one-person@example.test",
                                        OverrideApprovalRole.ENGINEERING_LEAD),
                                approval(
                                        "one-person@example.test",
                                        OverrideApprovalRole.PLATFORM_OPS)));
        var policy =
                new EmergencyOverridePolicy(
                        ignored -> Mono.just(evidence),
                        ignored -> Mono.just(true),
                        event -> {
                            audited.set(event);
                            return Mono.empty();
                        });

        StepVerifier.create(policy.authorize(freeze(), request()))
                .assertNext(
                        decision -> {
                            assertFalse(decision.permitted());
                            assertEquals("OVERRIDE_INVALID", decision.reason());
                        })
                .verifyComplete();
        assertEquals("OVERRIDE_INVALID", Objects.requireNonNull(audited.get()).outcome());
        assertEquals("INC-42", Objects.requireNonNull(audited.get()).incidentReference());
    }

    private static DeployFreezeDecision freeze() {
        var window =
                new SessionWindowResult(
                        SessionWindowState.OPEN,
                        SessionWindowReason.AUTHORITATIVE_OPEN,
                        "scheduling",
                        NOW,
                        Optional.empty());
        return new DeployFreezeDecision(false, 20, "SESSION_OPEN", window);
    }

    private static EmergencyOverrideRequest request() {
        return new EmergencyOverrideRequest(
                Optional.of("override-1"),
                "production",
                DIGEST,
                "attempt-1",
                "deployer@example.test",
                NOW);
    }

    private static EmergencyOverrideEvidence evidence(String incident) {
        return evidence(
                incident,
                List.of(
                        approval("lead@example.test", OverrideApprovalRole.ENGINEERING_LEAD),
                        approval("ops@example.test", OverrideApprovalRole.PLATFORM_OPS)));
    }

    private static EmergencyOverrideEvidence evidence(
            String incident, List<EmergencyOverrideApproval> approvals) {
        return new EmergencyOverrideEvidence(
                "override-1",
                DIGEST,
                true,
                incident,
                "production",
                DIGEST,
                "attempt-1",
                "Emergency release during protected window",
                NOW.minusSeconds(60),
                NOW.plusSeconds(300),
                approvals);
    }

    private static EmergencyOverrideApproval approval(String person, OverrideApprovalRole role) {
        return new EmergencyOverrideApproval(person, role, NOW, "signature", DIGEST);
    }
}
