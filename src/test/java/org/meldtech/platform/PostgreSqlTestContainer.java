package org.meldtech.platform;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class PostgreSqlTestContainer {

    private static final String IMAGE =
            "postgres:17@sha256:67f41722b7a8cbdb868a44a4995c846eddfdc2973bccb291ce937dce88ad5675";

    private static final PostgreSQLContainer INSTANCE = createContainer();

    private PostgreSqlTestContainer() {}

    public static PostgreSQLContainer instance() {
        return INSTANCE;
    }

    private static PostgreSQLContainer createContainer() {
        DockerImageName image = DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres");
        return new PostgreSQLContainer(image)
                .withCommand(
                        "postgres",
                        "-c",
                        "row_security=on",
                        "-c",
                        "default_transaction_isolation=read committed",
                        "-c",
                        "idle_in_transaction_session_timeout=30s",
                        "-c",
                        "statement_timeout=30s");
    }
}
