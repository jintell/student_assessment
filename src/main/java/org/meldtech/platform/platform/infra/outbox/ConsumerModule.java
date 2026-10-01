package org.meldtech.platform.platform.infra.outbox;

enum ConsumerModule {
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
    PLATFORM("platform");

    private final String schema;

    ConsumerModule(String schema) {
        this.schema = schema;
    }

    String schema() {
        return schema;
    }
}
