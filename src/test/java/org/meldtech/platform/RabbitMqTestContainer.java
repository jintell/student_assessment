package org.meldtech.platform;

import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

public final class RabbitMqTestContainer {

    private static final String IMAGE =
            "rabbitmq:3.13-management-alpine@sha256:606d8c0d6b3c18d1da9afc53bc7cdb2a8d5486df91b5a9830e9e07626c9ae281";

    private static final RabbitMQContainer INSTANCE = createContainer();

    private RabbitMqTestContainer() {}

    public static RabbitMQContainer instance() {
        return INSTANCE;
    }

    public static RabbitMQContainer newInstance() {
        return createContainer();
    }

    private static RabbitMQContainer createContainer() {
        DockerImageName image = DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("rabbitmq");
        return new RabbitMQContainer(image);
    }
}
