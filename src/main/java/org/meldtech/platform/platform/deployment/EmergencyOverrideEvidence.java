package org.meldtech.platform.platform.deployment;

import java.time.Instant;
import java.util.List;

public record EmergencyOverrideEvidence(
        String identifier,
        String evidenceDigest,
        boolean integrityVerified,
        String incidentReference,
        String targetEnvironment,
        String releaseManifestChecksum,
        String deploymentAttemptIdentifier,
        String reason,
        Instant issuedAt,
        Instant expiresAt,
        List<EmergencyOverrideApproval> approvals) {

    public EmergencyOverrideEvidence {
        approvals = List.copyOf(approvals);
    }
}
