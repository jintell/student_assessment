package org.meldtech.platform.platform.api;

/** Closed business-role targets; no login, composite, or audit-administration role is exposed. */
public enum SweepDataModule {
    TENANCY("tenancy"),
    IAM("iam"),
    ACADEMIC("academic"),
    PEOPLE("people"),
    QUESTION_BANK("questionbank"),
    AUTHORING("authoring"),
    EXAM_ACCESS("examaccess"),
    DELIVERY("delivery"),
    GRADING("grading"),
    RESULT("result"),
    CORRECTION("correction"),
    NOTIFICATION("notification"),
    OUTBOX("outbox");

    private final String moduleName;

    SweepDataModule(String moduleName) {
        this.moduleName = moduleName;
    }

    public String moduleName() {
        return moduleName;
    }
}
