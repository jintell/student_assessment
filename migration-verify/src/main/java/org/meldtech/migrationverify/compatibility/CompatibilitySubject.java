package org.meldtech.migrationverify.compatibility;

import java.util.Set;

public record CompatibilitySubject(
        String release,
        String previousImageDigest,
        String manifestChecksum,
        Set<String> releaseMigrations,
        Set<String> touchedRelations) {

    public CompatibilitySubject {
        releaseMigrations = Set.copyOf(releaseMigrations);
        touchedRelations = Set.copyOf(touchedRelations);
    }
}
