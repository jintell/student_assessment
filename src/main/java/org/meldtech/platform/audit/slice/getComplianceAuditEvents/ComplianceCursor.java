package org.meldtech.platform.audit.slice.getComplianceAuditEvents;

import java.time.Instant;
import java.util.Objects;

public record ComplianceCursor(
        int schemaVersion,
        String tenantId,
        String filterFingerprint,
        Instant asOf,
        Instant occurredAt,
        String retentionClass,
        int shardId,
        long sequence) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public ComplianceCursor {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unknown cursor schema version");
        }
        tenantId = requireText(tenantId, "tenantId");
        filterFingerprint = requireText(filterFingerprint, "filterFingerprint");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(occurredAt, "occurredAt");
        retentionClass = requireText(retentionClass, "retentionClass");
        if (shardId < 0 || sequence <= 0) {
            throw new IllegalArgumentException("cursor ordering tuple is invalid");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
