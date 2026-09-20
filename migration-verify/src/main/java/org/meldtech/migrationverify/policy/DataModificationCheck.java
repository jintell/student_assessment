package org.meldtech.migrationverify.policy;

import java.util.Optional;
import org.meldtech.migrationverify.core.MigrationHeader;
import org.meldtech.migrationverify.core.MigrationViolation;
import org.meldtech.migrationverify.core.ParsedMigrationStatement;
import org.meldtech.migrationverify.core.StatementKind;

public final class DataModificationCheck implements ForbiddenOperationCheck {

    @Override
    public Optional<MigrationViolation> evaluate(
            MigrationHeader header, ParsedMigrationStatement statement) {
        if (statement.kind() != StatementKind.DATA_MODIFICATION) {
            return Optional.empty();
        }
        return Optional.of(
                ForbiddenViolation.create(
                        "DATA_MODIFICATION_FORBIDDEN",
                        "migration scripts must not modify rows; use the reviewed backfill harness",
                        header,
                        statement));
    }
}
