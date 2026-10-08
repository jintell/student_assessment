package org.meldtech.platform.audit.slice.getComplianceAuditEvents;

@FunctionalInterface
public interface AuditQueryCatalogue {

    boolean isRegistered(Request request);
}
