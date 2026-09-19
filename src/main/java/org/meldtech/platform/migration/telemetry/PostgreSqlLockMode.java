package org.meldtech.platform.migration.telemetry;

public enum PostgreSqlLockMode {
    ACCESS_SHARE("AccessShareLock"),
    ROW_SHARE("RowShareLock"),
    ROW_EXCLUSIVE("RowExclusiveLock"),
    SHARE_UPDATE_EXCLUSIVE("ShareUpdateExclusiveLock"),
    SHARE("ShareLock"),
    SHARE_ROW_EXCLUSIVE("ShareRowExclusiveLock"),
    EXCLUSIVE("ExclusiveLock"),
    ACCESS_EXCLUSIVE("AccessExclusiveLock");

    private final String tag;

    PostgreSqlLockMode(String tag) {
        this.tag = tag;
    }

    String tag() {
        return tag;
    }
}
