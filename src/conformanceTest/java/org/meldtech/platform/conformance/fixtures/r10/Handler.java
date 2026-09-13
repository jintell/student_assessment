package org.meldtech.platform.conformance.fixtures.r10;

final class Handler {

    private final String databaseRole;

    Handler(String databaseRole) {
        this.databaseRole = databaseRole;
    }

    String handle() {
        return databaseRole;
    }
}
