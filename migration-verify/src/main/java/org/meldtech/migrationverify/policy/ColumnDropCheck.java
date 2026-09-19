package org.meldtech.migrationverify.policy;

import java.util.Optional;
import org.meldtech.migrationverify.core.MigrationHeader;
import org.meldtech.migrationverify.core.MigrationPhase;
import org.meldtech.migrationverify.core.MigrationViolation;
import org.meldtech.migrationverify.core.ParsedMigrationStatement;
import org.meldtech.migrationverify.core.StatementKind;

public final class ColumnDropCheck implements ForbiddenOperationCheck {

    @Override
    public Optional<MigrationViolation> evaluate(
            MigrationHeader header, ParsedMigrationStatement statement) {
        return statement.kind() == StatementKind.DROP_COLUMN
                        && header.phase() != MigrationPhase.CONTRACT
                ? Optional.of(
                        ForbiddenViolation.create(
                                "COLUMN_DROP_BEFORE_CONTRACT",
                                "a column may be dropped only in the separate CONTRACT release",
                                header,
                                statement))
                : Optional.empty();
    }
}
