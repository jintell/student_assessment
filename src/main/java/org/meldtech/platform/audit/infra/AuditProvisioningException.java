package org.meldtech.platform.audit.infra;

final class AuditProvisioningException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    AuditProvisioningException() {
        super("The pre-provisioned audit shard head is missing");
    }
}
