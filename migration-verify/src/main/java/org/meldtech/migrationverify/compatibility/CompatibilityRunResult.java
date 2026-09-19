package org.meldtech.migrationverify.compatibility;

import java.util.List;

public record CompatibilityRunResult(
        String requestedImageDigest,
        String resolvedImageDigest,
        List<CompatibilityCaseResult> cases) {

    public CompatibilityRunResult {
        cases = List.copyOf(cases);
    }
}
