package org.meldtech.migrationverify.core;

public record ParsedMigrationStatement(
        int ordinal,
        StatementKind kind,
        String relation,
        String objectName,
        String normalizedForm,
        boolean concurrent,
        boolean notValid,
        boolean cascade,
        boolean notNullWithoutDefault,
        boolean alterTable,
        int operationCount) {}
