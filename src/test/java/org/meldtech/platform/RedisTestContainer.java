package org.meldtech.platform;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public final class RedisTestContainer {

    private static final String IMAGE =
            "redis:8.8.3@sha256:b2ba68d56e7e7ea88b64f4eaf307bceafc012079f227ed795785f4283a592dc0";

    private static final GenericContainer<?> INSTANCE = createContainer();

    private RedisTestContainer() {}

    public static GenericContainer<?> instance() {
        return INSTANCE;
    }

    private static GenericContainer<?> createContainer() {
        return new GenericContainer<>(DockerImageName.parse(IMAGE)).withExposedPorts(6379);
    }
}
