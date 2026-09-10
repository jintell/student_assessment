package org.meldtech.platform.platform.infra.persistence;

enum AssumableDatabaseRole {
    TENANCY("app_tenancy", "tenancy"),
    IAM("app_iam", "iam"),
    ACADEMIC("app_academic", "academic"),
    PEOPLE("app_people", "people"),
    QUESTION_BANK("app_questionbank", "questionbank"),
    AUTHORING("app_authoring", "authoring"),
    EXAM_ACCESS("app_examaccess", "examaccess"),
    DELIVERY("app_delivery", "delivery"),
    GRADING("app_grading", "grading"),
    RESULT("app_result", "result"),
    CORRECTION("app_correction", "correction"),
    NOTIFICATION("app_notification", "notification"),
    EXAM_ENTRY("app_txn_examentry");

    private final String roleName;
    private final String schemaName;

    AssumableDatabaseRole(String roleName, String schemaName) {
        this.roleName = roleName;
        this.schemaName = schemaName;
    }

    AssumableDatabaseRole(String roleName) {
        this(roleName, "");
    }

    String roleName() {
        return roleName;
    }

    String searchPathStatement() {
        if (schemaName.isEmpty()) {
            return "SET LOCAL search_path = pg_catalog";
        }
        return "SET LOCAL search_path = pg_catalog, " + schemaName;
    }
}
