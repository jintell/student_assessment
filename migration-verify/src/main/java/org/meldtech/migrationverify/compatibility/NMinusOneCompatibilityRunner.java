package org.meldtech.migrationverify.compatibility;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

public final class NMinusOneCompatibilityRunner {

    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

    private final PreviousReleaseRuntimeFactory runtimeFactory;
    private final CompatibilityCaseRegistry cases;

    public NMinusOneCompatibilityRunner(
            PreviousReleaseRuntimeFactory runtimeFactory, CompatibilityCaseRegistry cases) {
        this.runtimeFactory = runtimeFactory;
        this.cases = cases;
    }

    public CompatibilityRunResult run(CompatibilitySubject subject) {
        if (!DIGEST.matcher(subject.previousImageDigest()).matches()) {
            throw new IllegalArgumentException(
                    "Previous release image must be an immutable sha256 digest");
        }
        List<CompatibilityCase> requiredCases =
                subject.touchedRelations().stream()
                        .sorted()
                        .map(cases::required)
                        .sorted(Comparator.comparing(CompatibilityCase::id))
                        .toList();
        try (PreviousReleaseApplication application =
                runtimeFactory.start(subject.previousImageDigest())) {
            if (!subject.previousImageDigest().equals(application.resolvedImageDigest())) {
                throw new IllegalStateException(
                        "Resolved N-1 image digest does not match the release manifest");
            }
            var results = new ArrayList<CompatibilityCaseResult>();
            for (CompatibilityCase compatibilityCase : requiredCases) {
                CompatibilityExecution execution = compatibilityCase.execute(application);
                if (!execution.passed()) {
                    throw new IllegalStateException(
                            "N-1 compatibility case failed: " + compatibilityCase.id());
                }
                results.add(
                        new CompatibilityCaseResult(
                                compatibilityCase.id(), compatibilityCase.relation(), execution));
            }
            return new CompatibilityRunResult(
                    subject.previousImageDigest(), application.resolvedImageDigest(), results);
        }
    }
}
