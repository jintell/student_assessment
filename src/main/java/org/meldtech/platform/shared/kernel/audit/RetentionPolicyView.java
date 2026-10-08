package org.meldtech.platform.shared.kernel.audit;

import java.time.Instant;
import java.util.Set;

@FunctionalInterface
public interface RetentionPolicyView {

    RetentionDecision resolve(
            Instant occurredAt,
            String eventType,
            String entityType,
            Set<RetentionClass> retentionCandidates);
}
