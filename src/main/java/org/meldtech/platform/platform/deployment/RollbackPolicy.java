package org.meldtech.platform.platform.deployment;

import java.util.Optional;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;

public final class RollbackPolicy {

    public static final String CONTRACT_ROLLBACK_FORBIDDEN = "CONTRACT_ROLLBACK_FORBIDDEN";

    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

    private final RollbackCompatibilityEvidenceQuery compatibilityEvidence;

    public RollbackPolicy(RollbackCompatibilityEvidenceQuery compatibilityEvidence) {
        this.compatibilityEvidence = compatibilityEvidence;
    }

    public Mono<RollbackDecision> evaluate(RollbackRequest request) {
        if (request.classification() == ReleaseClassification.CONTRACT) {
            return Mono.just(
                    new RollbackDecision(
                            false,
                            RollbackDecision.CONTRACT_REFUSAL_EXIT_CODE,
                            CONTRACT_ROLLBACK_FORBIDDEN,
                            "Release "
                                    + request.release()
                                    + " is CONTRACT and cannot be rolled back (ARC-OPS-008). "
                                    + "Keep the current schema, halt rollout, reference an incident, "
                                    + "and ship a new reviewed forward-fix migration. Do not edit an "
                                    + "applied migration or run schema undo.",
                            Optional.empty()));
        }
        if (!DIGEST.matcher(request.previousImageDigest()).matches()) {
            return Mono.just(refused("PREVIOUS_IMAGE_INVALID"));
        }
        return compatibilityEvidence
                .isGreen(
                        request.release(),
                        request.manifestChecksum(),
                        request.previousImageDigest())
                .defaultIfEmpty(false)
                .onErrorReturn(false)
                .map(
                        green ->
                                green
                                        ? new RollbackDecision(
                                                true,
                                                RollbackDecision.PERMITTED_EXIT_CODE,
                                                "CODE_ROLLBACK_PERMITTED",
                                                "Roll back code only to the retained N-1 image; do not "
                                                        + "roll back schema.",
                                                Optional.of(request.previousImageDigest()))
                                        : refused("N_MINUS_ONE_EVIDENCE_NOT_GREEN"));
    }

    private static RollbackDecision refused(String code) {
        return new RollbackDecision(
                false,
                RollbackDecision.COMPATIBILITY_REFUSAL_EXIT_CODE,
                code,
                "Code rollback is refused until matching N-1-against-N evidence is green.",
                Optional.empty());
    }
}
