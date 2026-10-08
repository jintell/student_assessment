package org.meldtech.platform.audit.slice.getComplianceAuditEvents;

public interface AuditCursorCodec {

    String encode(ComplianceCursor cursor);

    ComplianceCursor decode(String encoded);
}
