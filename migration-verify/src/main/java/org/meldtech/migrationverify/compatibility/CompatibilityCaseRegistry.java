package org.meldtech.migrationverify.compatibility;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CompatibilityCaseRegistry {

    private final Map<String, CompatibilityCase> casesByRelation;

    public CompatibilityCaseRegistry(Collection<CompatibilityCase> cases) {
        var indexed = new LinkedHashMap<String, CompatibilityCase>();
        for (CompatibilityCase compatibilityCase : cases) {
            if (indexed.putIfAbsent(compatibilityCase.relation(), compatibilityCase) != null) {
                throw new IllegalArgumentException(
                        "Duplicate N-1 compatibility case for " + compatibilityCase.relation());
            }
        }
        this.casesByRelation = Map.copyOf(indexed);
    }

    public CompatibilityCase required(String relation) {
        CompatibilityCase compatibilityCase = casesByRelation.get(relation);
        if (compatibilityCase == null) {
            throw new IllegalStateException(
                    "Missing N-1 read/write compatibility case for touched relation " + relation);
        }
        return compatibilityCase;
    }
}
