package org.meldtech.platform.shared.kernel.audit;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;

public record AuditEvent(
        String eventType,
        EntityRef entity,
        Set<RetentionClass> retentionCandidates,
        ObjectValue payload) {

    private static final Pattern EVENT_TYPE =
            Pattern.compile("[a-z][a-z0-9]*[.][A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*[.]v[1-9][0-9]*");

    public AuditEvent {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(retentionCandidates, "retentionCandidates");
        Objects.requireNonNull(payload, "payload");
        if (!EVENT_TYPE.matcher(eventType).matches()) {
            throw new IllegalArgumentException("eventType must be a stable versioned audit name");
        }
        if (retentionCandidates.isEmpty()) {
            throw new IllegalArgumentException("retentionCandidates must not be empty");
        }
        retentionCandidates = Set.copyOf(retentionCandidates);
    }
}
