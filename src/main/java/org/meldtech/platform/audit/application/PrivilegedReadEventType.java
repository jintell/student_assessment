package org.meldtech.platform.audit.application;

public enum PrivilegedReadEventType {
    COMPLIANCE_AUDIT_EVENTS("audit.COMPLIANCE_AUDIT_EVENTS_READ.v1"),
    CANDIDATE_PERSONAL_DATA("candidate.CANDIDATE_PERSONAL_DATA_READ.v1"),
    PIN_RETRIEVAL("security.PIN_RETRIEVAL_READ.v1"),
    GRADING_FAILURE_OPERATOR_VIEW("grading.GRADING_FAILURE_OPERATOR_READ.v1");

    private final String eventType;

    PrivilegedReadEventType(String eventType) {
        if (!eventType.matches("[a-z][a-z0-9]*[.][A-Z][A-Z0-9_]*_READ[.]v[1-9][0-9]*")) {
            throw new IllegalArgumentException(
                    "privileged read event must use the *_READ convention");
        }
        this.eventType = eventType;
    }

    public String eventType() {
        return eventType;
    }
}
