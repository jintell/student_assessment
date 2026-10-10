package org.meldtech.migrationverify.compatibility;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.meldtech.migrationverify.core.MigrationAnalysis;
import org.meldtech.migrationverify.core.ParsedMigrationStatement;
import org.meldtech.migrationverify.core.StatementKind;

public final class TouchedRelationCollector {

    private static final Pattern TABLE_PRIVILEGE = Pattern.compile("(?is)\\bON\\s+TABLE\\b");

    public Set<String> collect(Collection<MigrationAnalysis> analyses) {
        List<ParsedMigrationStatement> statements =
                analyses.stream().flatMap(analysis -> analysis.statements().stream()).toList();
        Set<String> newTables = new TreeSet<>();
        statements.stream()
                .filter(statement -> statement.kind() == StatementKind.CREATE_TABLE)
                .map(ParsedMigrationStatement::relation)
                .filter(relation -> !relation.isBlank())
                .forEach(newTables::add);

        var relations = new TreeSet<String>();
        statements.stream()
                .filter(TouchedRelationCollector::requiresReadWriteCompatibility)
                .map(ParsedMigrationStatement::relation)
                .filter(relation -> !relation.isBlank())
                .filter(relation -> !newTables.contains(relation))
                .forEach(relations::add);
        return Set.copyOf(relations);
    }

    private static boolean requiresReadWriteCompatibility(ParsedMigrationStatement statement) {
        return switch (statement.kind()) {
            case CREATE_INDEX,
                    CREATE_TRIGGER,
                    CREATE_POLICY,
                    ROW_LEVEL_SECURITY,
                    ADD_COLUMN,
                    ADD_CONSTRAINT,
                    VALIDATE_CONSTRAINT,
                    SET_DEFAULT,
                    DROP_COLUMN,
                    DROP_CONSTRAINT,
                    DROP_DEFAULT,
                    DROP_TABLE,
                    RENAME_COLUMN ->
                    true;
            case GRANT, REVOKE -> TABLE_PRIVILEGE.matcher(statement.normalizedForm()).find();
            case OTHER -> statement.alterTable();
            case CREATE_TABLE,
                    CREATE_EXTENSION,
                    CREATE_FUNCTION,
                    DROP_INDEX,
                    COMMENT,
                    INVOKE_FUNCTION,
                    PROCEDURAL_BLOCK,
                    DATA_MODIFICATION ->
                    false;
        };
    }
}
