package org.meldtech.migrationverify.policy;

import java.util.Optional;
import org.meldtech.migrationverify.core.MigrationHeader;
import org.meldtech.migrationverify.core.MigrationViolation;
import org.meldtech.migrationverify.core.ParsedMigrationStatement;
import org.meldtech.migrationverify.core.StatementKind;

public final class NonConcurrentIndexCheck implements ForbiddenOperationCheck {

    @Override
    public Optional<MigrationViolation> evaluate(
            MigrationHeader header, ParsedMigrationStatement statement) {
        return statement.kind() == StatementKind.CREATE_INDEX && !statement.concurrent()
                ? Optional.of(
                        ForbiddenViolation.create(
                                "NON_CONCURRENT_INDEX",
                                "CREATE INDEX must use CONCURRENTLY",
                                header,
                                statement))
                : Optional.empty();
    }
}
