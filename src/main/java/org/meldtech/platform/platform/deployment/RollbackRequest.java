package org.meldtech.platform.platform.deployment;

public record RollbackRequest(
        String release,
        ReleaseClassification classification,
        String previousImageDigest,
        String manifestChecksum,
        String environment,
        String requester) {}
