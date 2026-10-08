package org.meldtech.platform.shared.kernel.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record RetentionDecision(
        String policyKey,
        long policyVersion,
        Instant effectiveFrom,
        Optional<Instant> effectiveUntil,
        Map<RetentionClass, RetentionHorizon> applicableHorizons,
        RetentionClass winningClass) {

    public RetentionDecision {
        Objects.requireNonNull(policyKey, "policyKey");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(effectiveUntil, "effectiveUntil");
        Objects.requireNonNull(applicableHorizons, "applicableHorizons");
        Objects.requireNonNull(winningClass, "winningClass");
        if (policyKey.isBlank()) {
            throw new IllegalArgumentException("policyKey must not be blank");
        }
        if (policyVersion <= 0) {
            throw new IllegalArgumentException("policyVersion must be positive");
        }
        if (effectiveUntil.isPresent() && !effectiveUntil.orElseThrow().isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveUntil must be after effectiveFrom");
        }
        applicableHorizons = Map.copyOf(applicableHorizons);
    }
}
