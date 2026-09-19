package org.meldtech.migrationverify.core;

public enum StatementKind {
    CREATE_TABLE,
    ADD_COLUMN,
    ADD_CONSTRAINT,
    CREATE_INDEX,
    VALIDATE_CONSTRAINT,
    SET_DEFAULT,
    DROP_COLUMN,
    DROP_CONSTRAINT,
    DROP_DEFAULT,
    DROP_INDEX,
    DROP_TABLE,
    RENAME_COLUMN,
    COMMENT,
    OTHER
}
