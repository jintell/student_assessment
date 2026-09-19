package org.meldtech.migrationverify.compatibility;

public interface CompatibilityCase {

    String id();

    String relation();

    CompatibilityExecution execute(PreviousReleaseApplication application);
}
