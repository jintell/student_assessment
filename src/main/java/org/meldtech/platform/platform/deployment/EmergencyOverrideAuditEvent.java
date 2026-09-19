package org.meldtech.platform.platform.deployment;

import java.time.Instant;
import java.util.List;
import org.meldtech.platform.platform.api.SessionWindowReason;
import org.meldtech.platform.platform.api.SessionWindowState;

public record EmergencyOverrideAuditEvent(
        String evidenceIdentifier,
        String evidenceDigest,
        String incidentReference,
        List<String> approvers,
        String requester,
        SessionWindowState windowState,
        SessionWindowReason windowReason,
        String releaseManifestChecksum,
        String targetEnvironment,
        String deploymentAttemptIdentifier,
        Instant decisionTime,
        String outcome) {

    public EmergencyOverrideAuditEvent {
        approvers = List.copyOf(approvers);
    }
}
