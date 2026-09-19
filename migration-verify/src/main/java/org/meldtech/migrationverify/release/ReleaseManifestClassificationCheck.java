package org.meldtech.migrationverify.release;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ReleaseManifestClassificationCheck {

    private static final Set<String> CLASSIFICATIONS = Set.of("EXPAND", "MIGRATE", "CONTRACT");

    public void verify(String declaredClassification, List<String> migrationClassifications) {
        requireKnown(declaredClassification, "release classification");
        var discovered = new LinkedHashSet<String>();
        for (String classification : migrationClassifications) {
            requireKnown(classification, "migration classification");
            discovered.add(classification);
        }
        if (discovered.size() > 1) {
            throw new IllegalArgumentException(
                    "A release must have exactly one migration classification; found "
                            + discovered);
        }
        if (!discovered.isEmpty() && !discovered.contains(declaredClassification)) {
            throw new IllegalArgumentException(
                    "Declared release classification "
                            + declaredClassification
                            + " does not match migration classification "
                            + discovered.getFirst());
        }
    }

    private static void requireKnown(String classification, String field) {
        if (classification == null || !CLASSIFICATIONS.contains(classification)) {
            throw new IllegalArgumentException("Unknown " + field + ": " + classification);
        }
    }
}
