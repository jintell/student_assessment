package org.meldtech.migrationverify.compatibility;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import org.meldtech.migrationverify.core.MigrationAnalysis;
import org.meldtech.migrationverify.core.ParsedMigrationStatement;

public final class TouchedRelationCollector {

    public Set<String> collect(Collection<MigrationAnalysis> analyses) {
        var relations = new TreeSet<String>();
        for (MigrationAnalysis analysis : analyses) {
            analysis.statements().stream()
                    .map(ParsedMigrationStatement::relation)
                    .filter(relation -> !relation.isBlank())
                    .forEach(relations::add);
        }
        return Set.copyOf(relations);
    }
}
