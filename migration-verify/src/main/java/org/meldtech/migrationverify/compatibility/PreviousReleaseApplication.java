package org.meldtech.migrationverify.compatibility;

public interface PreviousReleaseApplication extends AutoCloseable {

    String resolvedImageDigest();

    @Override
    void close();
}
