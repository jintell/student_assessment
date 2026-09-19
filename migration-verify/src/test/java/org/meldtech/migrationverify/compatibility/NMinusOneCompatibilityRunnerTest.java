package org.meldtech.migrationverify.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NMinusOneCompatibilityRunnerTest {

    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final CompatibilityExecution PASS =
            new CompatibilityExecution(true, true, true, true);

    @Test
    void executesReadAndWriteConformanceForEveryTouchedRelation() {
        var executed = new java.util.ArrayList<String>();
        var registry =
                new CompatibilityCaseRegistry(
                        List.of(
                                compatibilityCase("answer", "delivery.answer", executed),
                                compatibilityCase("attempt", "delivery.attempt", executed)));
        var runner = new NMinusOneCompatibilityRunner(ignored -> application(DIGEST), registry);
        var subject =
                new CompatibilitySubject(
                        "2026.09.0",
                        DIGEST,
                        "sha256:" + "b".repeat(64),
                        Set.of("delivery.answer", "delivery.attempt"));

        var result = runner.run(subject);

        assertEquals(List.of("answer", "attempt"), executed);
        assertEquals(2, result.cases().size());
    }

    @Test
    void refusesToStartWhenATouchedRelationHasNoCase() {
        var runner =
                new NMinusOneCompatibilityRunner(
                        ignored -> application(DIGEST), new CompatibilityCaseRegistry(List.of()));
        var subject =
                new CompatibilitySubject(
                        "2026.09.0",
                        DIGEST,
                        "sha256:" + "b".repeat(64),
                        Set.of("delivery.answer_operation"));

        assertThrows(IllegalStateException.class, () -> runner.run(subject));
    }

    @Test
    void refusesAResolvedDigestMismatch() {
        var registry =
                new CompatibilityCaseRegistry(
                        List.of(compatibilityCase("answer", "delivery.answer", List.of())));
        var runner =
                new NMinusOneCompatibilityRunner(
                        ignored -> application("sha256:" + "c".repeat(64)), registry);
        var subject =
                new CompatibilitySubject(
                        "2026.09.0", DIGEST, "sha256:" + "b".repeat(64), Set.of("delivery.answer"));

        assertThrows(IllegalStateException.class, () -> runner.run(subject));
    }

    private static CompatibilityCase compatibilityCase(
            String id, String relation, List<String> executed) {
        return new CompatibilityCase() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String relation() {
                return relation;
            }

            @Override
            public CompatibilityExecution execute(PreviousReleaseApplication application) {
                executed.add(id);
                return PASS;
            }
        };
    }

    private static PreviousReleaseApplication application(String digest) {
        return new PreviousReleaseApplication() {
            @Override
            public String resolvedImageDigest() {
                return digest;
            }

            @Override
            public void close() {}
        };
    }
}
