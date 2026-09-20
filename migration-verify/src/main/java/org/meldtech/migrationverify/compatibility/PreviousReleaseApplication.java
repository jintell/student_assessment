package org.meldtech.migrationverify.compatibility;

public interface PreviousReleaseApplication extends AutoCloseable {

    String resolvedImageDigest();

    boolean containsMigration(String migrationPath);

    CompatibilityExecution execute(String caseId);

    @Override
    void close();
}
