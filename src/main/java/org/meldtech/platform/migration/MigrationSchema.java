package org.meldtech.platform.migration;

enum MigrationSchema {
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
    AUDIT("audit"),
    OUTBOX("outbox"),
    PLATFORM("platform");

    private final String schemaName;

    MigrationSchema(String schemaName) {
        this.schemaName = schemaName;
    }

    String schemaName() {
        return schemaName;
    }

    String location() {
        return "classpath:db/migration/" + schemaName;
    }

    String historyTable() {
        return "flyway_schema_history_" + schemaName;
    }
}
