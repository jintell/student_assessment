package org.meldtech.platform.shared.api;

/** Closed ADR-023 synchronous cross-module write flow and composite-role enumeration. */
public enum AtomicCrossModuleFlow {
    EXAM_ENTRY("examaccess.verifyPinAndStartAttempt", "cbt_exam_entry");

    private final String flow;
    private final String compositeRole;

    AtomicCrossModuleFlow(String flow, String compositeRole) {
        this.flow = flow;
        this.compositeRole = compositeRole;
    }

    public String flow() {
        return flow;
    }

    public String compositeRole() {
        return compositeRole;
    }
}
