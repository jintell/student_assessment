package org.meldtech.migrationverify.compatibility;

import java.util.Set;

public record CompatibilitySubject(
        String release,
        String previousImageDigest,
        String manifestChecksum,
        Set<String> touchedRelations) {

    public CompatibilitySubject {
        touchedRelations = Set.copyOf(touchedRelations);
    }
}
