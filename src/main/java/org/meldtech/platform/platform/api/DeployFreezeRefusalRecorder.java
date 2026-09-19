package org.meldtech.platform.platform.api;

@FunctionalInterface
public interface DeployFreezeRefusalRecorder {

    void record(DeployFreezeRefusalReason reason);
}
