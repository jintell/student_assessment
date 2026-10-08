package org.meldtech.platform.audit.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.audit.RetentionDecision;
import org.meldtech.platform.shared.kernel.audit.RetentionHorizon;
import org.meldtech.platform.shared.kernel.audit.RetentionPolicyView;

public final class RetentionResolver {

    private static final Map<RetentionClass, Integer> TIE_PRECEDENCE =
            Map.of(
                    RetentionClass.RESULT_CORRECTION_EVIDENCE, 4,
                    RetentionClass.RESULT_PUBLICATION_EVIDENCE, 3,
                    RetentionClass.GENERAL_AUDIT_EVENT, 2,
                    RetentionClass.PIN_SECURITY_EVENT, 1);
    private static final Comparator<Map.Entry<RetentionClass, RetentionHorizon>> LONGEST_FIRST =
            Comparator.<Map.Entry<RetentionClass, RetentionHorizon>, Boolean>comparing(
                            entry -> entry.getValue().isIndefinite())
                    .thenComparing(entry -> entry.getValue().retainedUntil().orElse(Instant.MIN))
                    .thenComparingInt(entry -> TIE_PRECEDENCE.get(entry.getKey()));

    private final RetentionPolicyView policyView;

    public RetentionResolver(RetentionPolicyView policyView) {
        this.policyView = Objects.requireNonNull(policyView, "policyView");
    }

    public ResolvedRetention resolve(AuditEvent event, Instant occurredAt) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(occurredAt, "occurredAt");
        RetentionDecision decision =
                Objects.requireNonNull(
                        policyView.resolve(
                                occurredAt,
                                event.eventType(),
                                event.entity().entityType(),
                                event.retentionCandidates()),
                        "Retention policy view returned no decision");
        validateEffectiveAt(decision, occurredAt);
        validateCandidates(decision.applicableHorizons(), event.retentionCandidates());

        Map.Entry<RetentionClass, RetentionHorizon> winner =
                decision.applicableHorizons().entrySet().stream()
                        .filter(entry -> event.retentionCandidates().contains(entry.getKey()))
                        .max(LONGEST_FIRST)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Retention decision has no candidate"));
        if (winner.getKey() != decision.winningClass()) {
            throw new IllegalStateException(
                    "Retention policy winner is inconsistent with longest-wins");
        }
        return new ResolvedRetention(
                winner.getKey(), decision.policyKey(), decision.policyVersion(), winner.getValue());
    }

    private static void validateEffectiveAt(RetentionDecision decision, Instant occurredAt) {
        if (occurredAt.isBefore(decision.effectiveFrom())
                || decision.effectiveUntil()
                        .map(until -> !occurredAt.isBefore(until))
                        .orElse(false)) {
            throw new IllegalStateException(
                    "Retention policy version is not effective at occurrence time");
        }
    }

    private static void validateCandidates(
            Map<RetentionClass, RetentionHorizon> horizons, Set<RetentionClass> candidates) {
        if (!horizons.keySet().containsAll(candidates)) {
            throw new IllegalStateException(
                    "Retention policy did not evaluate every required candidate");
        }
        horizons.forEach(
                (retentionClass, horizon) -> {
                    Objects.requireNonNull(retentionClass, "retentionClass");
                    Objects.requireNonNull(horizon, "retentionHorizon");
                });
    }
}
