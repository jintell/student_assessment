package org.meldtech.platform.audit.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.meldtech.platform.shared.kernel.identity.TenantId;

public record AuditVerificationFinding(
        UUID reportId,
        TenantId tenantId,
        String category,
        String snapshotReference,
        String databaseLsn,
        List<String> affectedIdentities,
        Instant detectedAt) {

    public AuditVerificationFinding {
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(tenantId, "tenantId");
        category = requireText(category, "category");
        snapshotReference = requireText(snapshotReference, "snapshotReference");
        databaseLsn = requireText(databaseLsn, "databaseLsn");
        affectedIdentities = List.copyOf(affectedIdentities);
        if (affectedIdentities.isEmpty()) {
            throw new IllegalArgumentException("affectedIdentities must not be empty");
        }
        Objects.requireNonNull(detectedAt, "detectedAt");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
