package org.meldtech.migrationverify.policy;

public final class InvalidMigrationHeaderException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public InvalidMigrationHeaderException(String message) {
        super(message);
    }
}
