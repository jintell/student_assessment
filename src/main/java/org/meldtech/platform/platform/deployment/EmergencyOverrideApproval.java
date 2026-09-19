package org.meldtech.platform.platform.deployment;

import java.time.Instant;

public record EmergencyOverrideApproval(
        String approver,
        OverrideApprovalRole role,
        Instant approvedAt,
        String signature,
        String evidenceDigest) {}
