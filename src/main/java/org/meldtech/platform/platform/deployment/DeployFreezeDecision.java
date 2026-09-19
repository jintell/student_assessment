package org.meldtech.platform.platform.deployment;

import org.meldtech.platform.platform.api.SessionWindowResult;

public record DeployFreezeDecision(
        boolean permitted, int exitCode, String refusalReason, SessionWindowResult window) {

    public static final int PERMITTED_EXIT_CODE = 0;
    public static final int SESSION_OPEN_EXIT_CODE = 20;
    public static final int SESSION_UNKNOWN_EXIT_CODE = 21;
}
