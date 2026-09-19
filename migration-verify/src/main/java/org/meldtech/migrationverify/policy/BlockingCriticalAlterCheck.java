package org.meldtech.migrationverify.policy;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.meldtech.migrationverify.core.MigrationHeader;
import org.meldtech.migrationverify.core.MigrationViolation;
import org.meldtech.migrationverify.core.ParsedMigrationStatement;
import org.meldtech.migrationverify.core.StatementKind;
import org.meldtech.migrationverify.port.ExamCriticalRelationLookup;

public final class BlockingCriticalAlterCheck implements ForbiddenOperationCheck {

    private static final Set<StatementKind> ONLINE_ALTER_SHAPES =
            EnumSet.of(StatementKind.ADD_COLUMN, StatementKind.ADD_CONSTRAINT);

    private final ExamCriticalRelationLookup relations;

    public BlockingCriticalAlterCheck(ExamCriticalRelationLookup relations) {
        this.relations = relations;
    }

    @Override
    public Optional<MigrationViolation> evaluate(
            MigrationHeader header, ParsedMigrationStatement statement) {
        return statement.alterTable()
                        && relations.isCritical(statement.relation())
                        && !ONLINE_ALTER_SHAPES.contains(statement.kind())
                ? Optional.of(
                        ForbiddenViolation.create(
                                "BLOCKING_CRITICAL_ALTER",
                                "blocking ALTER TABLE is forbidden on " + statement.relation(),
                                header,
                                statement))
                : Optional.empty();
    }
}
