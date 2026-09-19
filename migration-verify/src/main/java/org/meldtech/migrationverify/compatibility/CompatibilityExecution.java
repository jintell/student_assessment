package org.meldtech.migrationverify.compatibility;

public record CompatibilityExecution(
        boolean existingReadPassed,
        boolean oldRepresentationWritePassed,
        boolean readBackPassed,
        boolean persistedInvariantPassed) {

    public boolean passed() {
        return existingReadPassed
                && oldRepresentationWritePassed
                && readBackPassed
                && persistedInvariantPassed;
    }
}
