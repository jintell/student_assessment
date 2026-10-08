package org.meldtech.platform.audit.application;

@FunctionalInterface
public interface AuditSealTelemetry {

    AuditSealTelemetry NO_OP = () -> {};

    void compareAndSwapRetry();
}
