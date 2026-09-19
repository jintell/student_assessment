package org.meldtech.migrationverify.policy;

import java.util.Optional;
import org.meldtech.migrationverify.core.MigrationHeader;
import org.meldtech.migrationverify.core.MigrationViolation;
import org.meldtech.migrationverify.core.ParsedMigrationStatement;
import org.meldtech.migrationverify.core.StatementKind;

public final class ColumnRenameCheck implements ForbiddenOperationCheck {

    @Override
    public Optional<MigrationViolation> evaluate(
            MigrationHeader header, ParsedMigrationStatement statement) {
        return statement.kind() == StatementKind.RENAME_COLUMN
                ? Optional.of(
                        ForbiddenViolation.create(
                                "COLUMN_RENAME_FORBIDDEN",
                                "rename through expand, migrate, and contract columns instead",
                                header,
                                statement))
                : Optional.empty();
    }
}
