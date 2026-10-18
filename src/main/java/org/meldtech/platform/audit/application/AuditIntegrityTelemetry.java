package org.meldtech.platform.audit.application;

public interface AuditIntegrityTelemetry extends AuditSealTelemetry {

    void verificationCompleted(boolean successful);

    void emissionFailed();

    void checkpointOverdueCount(long count);

    void sealCompleted(AuditEpochSealingJob.JobResult result);

    void dispositionCompleted(DispositionOutcome outcome);

    void rootForkDetected();

    void rootSequenceRange(long minimum, long maximum);

    void invalidateSamples();

    enum DispositionOutcome {
        DISPOSED,
        HOLD_SUSPENDED,
        VERIFY_FAILED
    }
}
