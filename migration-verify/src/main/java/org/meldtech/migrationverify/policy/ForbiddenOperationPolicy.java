package org.meldtech.migrationverify.policy;

import java.util.ArrayList;
import java.util.List;
import org.meldtech.migrationverify.core.MigrationHeader;
import org.meldtech.migrationverify.core.MigrationViolation;
import org.meldtech.migrationverify.core.ParsedMigrationStatement;

public final class ForbiddenOperationPolicy {

    private final List<ForbiddenOperationCheck> checks;

    public ForbiddenOperationPolicy(List<ForbiddenOperationCheck> checks) {
        this.checks = List.copyOf(checks);
    }

    public List<MigrationViolation> evaluate(
            MigrationHeader header, List<ParsedMigrationStatement> statements) {
        var violations = new ArrayList<MigrationViolation>();
        for (ParsedMigrationStatement statement : statements) {
            for (ForbiddenOperationCheck check : checks) {
                check.evaluate(header, statement).ifPresent(violations::add);
            }
        }
        return List.copyOf(violations);
    }
}
