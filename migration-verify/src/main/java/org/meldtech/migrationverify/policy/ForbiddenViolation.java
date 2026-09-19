package org.meldtech.migrationverify.policy;

import org.meldtech.migrationverify.core.MigrationHeader;
import org.meldtech.migrationverify.core.MigrationViolation;
import org.meldtech.migrationverify.core.ParsedMigrationStatement;

final class ForbiddenViolation {

    private ForbiddenViolation() {}

    static MigrationViolation create(
            String code,
            String reason,
            MigrationHeader header,
            ParsedMigrationStatement statement) {
        return new MigrationViolation(
                code,
                statement.ordinal(),
                header.source()
                        + ": statement "
                        + statement.ordinal()
                        + " violates "
                        + code
                        + ": "
                        + reason);
    }
}
