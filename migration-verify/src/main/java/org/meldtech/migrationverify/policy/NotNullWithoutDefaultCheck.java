package org.meldtech.migrationverify.policy;

import java.util.Optional;
import org.meldtech.migrationverify.core.MigrationHeader;
import org.meldtech.migrationverify.core.MigrationViolation;
import org.meldtech.migrationverify.core.ParsedMigrationStatement;

public final class NotNullWithoutDefaultCheck implements ForbiddenOperationCheck {

    @Override
    public Optional<MigrationViolation> evaluate(
            MigrationHeader header, ParsedMigrationStatement statement) {
        return statement.notNullWithoutDefault()
                ? Optional.of(
                        ForbiddenViolation.create(
                                "NOT_NULL_WITHOUT_DEFAULT",
                                "NOT NULL requires a constant default for existing and old-version rows",
                                header,
                                statement))
                : Optional.empty();
    }
}
