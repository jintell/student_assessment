package org.meldtech.migrationverify.core;

import java.util.List;

public record MigrationAnalysis(
        MigrationHeader header,
        List<ParsedMigrationStatement> statements,
        List<MigrationViolation> violations) {

    public boolean valid() {
        return violations.isEmpty();
    }
}
