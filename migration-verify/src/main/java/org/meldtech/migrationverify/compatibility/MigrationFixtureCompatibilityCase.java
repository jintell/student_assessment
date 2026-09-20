package org.meldtech.migrationverify.compatibility;

public final class MigrationFixtureCompatibilityCase implements CompatibilityCase {

    public static final String ID = "platform.migration_fixture.v6";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String relation() {
        return "platform.migration_fixture";
    }

    @Override
    public CompatibilityExecution execute(PreviousReleaseApplication application) {
        return application.execute(ID);
    }
}
