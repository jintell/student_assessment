package org.meldtech.platform.platform.deployment;

import java.util.Optional;

public record RollbackDecision(
        boolean permitted,
        int exitCode,
        String code,
        String message,
        Optional<String> targetImageDigest) {

    public static final int PERMITTED_EXIT_CODE = 0;
    public static final int CONTRACT_REFUSAL_EXIT_CODE = 30;
    public static final int COMPATIBILITY_REFUSAL_EXIT_CODE = 31;
}
