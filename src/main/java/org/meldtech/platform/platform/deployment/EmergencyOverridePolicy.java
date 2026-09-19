package org.meldtech.platform.platform.deployment;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.meldtech.platform.platform.api.SessionWindowResult;
import reactor.core.publisher.Mono;

public final class EmergencyOverridePolicy {

    private final EmergencyOverrideEvidenceQuery evidenceQuery;
    private final ActiveIncidentQuery incidentQuery;
    private final EmergencyOverrideAuditTrail auditTrail;

    public EmergencyOverridePolicy(
            EmergencyOverrideEvidenceQuery evidenceQuery,
            ActiveIncidentQuery incidentQuery,
            EmergencyOverrideAuditTrail auditTrail) {
        this.evidenceQuery = evidenceQuery;
        this.incidentQuery = incidentQuery;
        this.auditTrail = auditTrail;
    }

    public Mono<EmergencyOverrideDecision> authorize(
            DeployFreezeDecision freezeDecision, EmergencyOverrideRequest request) {
        if (freezeDecision.permitted()) {
            return Mono.just(new EmergencyOverrideDecision(true, "OVERRIDE_NOT_REQUIRED"));
        }
        String evidenceIdentifier =
                request.evidenceIdentifier().filter(value -> !value.isBlank()).orElse("");
        if (evidenceIdentifier.isEmpty()) {
            return denied(freezeDecision.window(), request, Optional.empty(), "OVERRIDE_MISSING");
        }
        return evidenceQuery
                .find(evidenceIdentifier)
                .flatMap(
                        evidence ->
                                validateEvidence(evidence, request)
                                        .flatMap(
                                                active ->
                                                        active
                                                                ? permit(
                                                                        freezeDecision.window(),
                                                                        request,
                                                                        evidence)
                                                                : denied(
                                                                        freezeDecision.window(),
                                                                        request,
                                                                        Optional.of(evidence),
                                                                        "OVERRIDE_INVALID")))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        denied(
                                                freezeDecision.window(),
                                                request,
                                                Optional.empty(),
                                                "OVERRIDE_INVALID")))
                .onErrorResume(
                        ignored ->
                                denied(
                                        freezeDecision.window(),
                                        request,
                                        Optional.empty(),
                                        "EVIDENCE_UNAVAILABLE"));
    }

    private Mono<Boolean> validateEvidence(
            EmergencyOverrideEvidence evidence, EmergencyOverrideRequest request) {
        if (!evidence.integrityVerified()
                || !request.evidenceIdentifier().orElse("").equals(evidence.identifier())
                || evidence.incidentReference() == null
                || evidence.incidentReference().isBlank()
                || !evidence.targetEnvironment().equals(request.targetEnvironment())
                || !evidence.releaseManifestChecksum().equals(request.releaseManifestChecksum())
                || !evidence.deploymentAttemptIdentifier()
                        .equals(request.deploymentAttemptIdentifier())
                || evidence.issuedAt().isAfter(request.decisionTime())
                || !evidence.expiresAt().isAfter(request.decisionTime())
                || !validApprovals(evidence, request.requester())) {
            return Mono.just(false);
        }
        return incidentQuery
                .isActive(evidence.incidentReference())
                .defaultIfEmpty(false)
                .onErrorReturn(false);
    }

    private static boolean validApprovals(EmergencyOverrideEvidence evidence, String requester) {
        List<EmergencyOverrideApproval> approvals = evidence.approvals();
        Set<OverrideApprovalRole> roles =
                approvals.stream().map(EmergencyOverrideApproval::role).collect(Collectors.toSet());
        Set<String> people =
                approvals.stream()
                        .map(EmergencyOverrideApproval::approver)
                        .collect(Collectors.toSet());
        return approvals.size() == 2
                && roles.equals(EnumSet.allOf(OverrideApprovalRole.class))
                && people.size() == 2
                && !people.contains(requester)
                && approvals.stream()
                        .allMatch(
                                approval ->
                                        approval.approver() != null
                                                && !approval.approver().isBlank()
                                                && approval.signature() != null
                                                && !approval.signature().isBlank()
                                                && evidence.evidenceDigest()
                                                        .equals(approval.evidenceDigest()));
    }

    private Mono<EmergencyOverrideDecision> permit(
            SessionWindowResult window,
            EmergencyOverrideRequest request,
            EmergencyOverrideEvidence evidence) {
        var event = event(window, request, Optional.of(evidence), "PERMITTED");
        return auditTrail
                .record(event)
                .thenReturn(new EmergencyOverrideDecision(true, "EMERGENCY_OVERRIDE"))
                .onErrorReturn(new EmergencyOverrideDecision(false, "AUDIT_FAILED"));
    }

    private Mono<EmergencyOverrideDecision> denied(
            SessionWindowResult window,
            EmergencyOverrideRequest request,
            Optional<EmergencyOverrideEvidence> evidence,
            String reason) {
        var decision = new EmergencyOverrideDecision(false, reason);
        return auditTrail
                .record(event(window, request, evidence, reason))
                .thenReturn(decision)
                .onErrorReturn(decision);
    }

    private static EmergencyOverrideAuditEvent event(
            SessionWindowResult window,
            EmergencyOverrideRequest request,
            Optional<EmergencyOverrideEvidence> evidence,
            String outcome) {
        String identifier =
                evidence.map(EmergencyOverrideEvidence::identifier)
                        .orElseGet(() -> request.evidenceIdentifier().orElse(""));
        String digest = evidence.map(EmergencyOverrideEvidence::evidenceDigest).orElse("");
        String incident = evidence.map(EmergencyOverrideEvidence::incidentReference).orElse("");
        List<String> approvers =
                evidence.map(
                                present ->
                                        present.approvals().stream()
                                                .map(EmergencyOverrideApproval::approver)
                                                .sorted()
                                                .toList())
                        .orElseGet(List::of);
        return new EmergencyOverrideAuditEvent(
                identifier,
                digest,
                incident,
                approvers,
                request.requester(),
                window.state(),
                window.reason(),
                request.releaseManifestChecksum(),
                request.targetEnvironment(),
                request.deploymentAttemptIdentifier(),
                request.decisionTime(),
                outcome);
    }
}
