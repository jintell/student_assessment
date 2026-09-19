package org.meldtech.migrationverify.compatibility;

@FunctionalInterface
public interface PreviousReleaseRuntimeFactory {

    PreviousReleaseApplication start(String immutableImageDigest);
}
