package org.meldtech.platform.audit.infra;

final class AuditAppendIntegrityException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    AuditAppendIntegrityException(long insertedRows, long updatedRows) {
        super(
                "Atomic audit append affected "
                        + insertedRows
                        + " event rows and "
                        + updatedRows
                        + " head rows");
    }
}
