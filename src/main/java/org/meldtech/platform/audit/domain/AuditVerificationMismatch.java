package org.meldtech.platform.audit.domain;

public final class AuditVerificationMismatch extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuditVerificationMismatch(String message) {
        super(message);
    }
}
