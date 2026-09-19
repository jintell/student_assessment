package org.meldtech.migrationverify.policy;

import java.util.Optional;
import org.meldtech.migrationverify.core.MigrationHeader;
import org.meldtech.migrationverify.core.MigrationViolation;
import org.meldtech.migrationverify.core.ParsedMigrationStatement;

public interface ForbiddenOperationCheck {

    Optional<MigrationViolation> evaluate(
            MigrationHeader header, ParsedMigrationStatement statement);
}
