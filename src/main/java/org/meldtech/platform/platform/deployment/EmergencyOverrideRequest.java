package org.meldtech.platform.platform.deployment;

import java.time.Instant;
import java.util.Optional;

public record EmergencyOverrideRequest(
        Optional<String> evidenceIdentifier,
        String targetEnvironment,
        String releaseManifestChecksum,
        String deploymentAttemptIdentifier,
        String requester,
        Instant decisionTime) {}
