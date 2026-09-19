package org.meldtech.migrationverify.core;

public record MigrationViolation(String code, int statementOrdinal, String message) {}
