package org.meldtech.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

/** Explicit local external inputs for full-context tests; production defaults stay fail-closed. */
public final class RuntimeTestEnvironment
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        try {
            Path root = Files.createTempDirectory("runtime-test-");
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            Files.write(root.resolve("hash"), secret);
            Files.writeString(root.resolve("memory.max"), "1073741824");
            Files.writeString(root.resolve("memory.current"), "0");
            var postgres = PostgreSqlTestContainer.instance();
            postgres.start();
            TestPropertyValues.of(
                            "cbt.runtime.cgroup-root=" + root,
                            "cbt.runtime.drain-marker=" + root.resolve("draining"),
                            "cbt.observability.candidate-hash.secret-reference="
                                    + root.resolve("hash"),
                            "CBT_DATABASE_URL=r2dbc:postgresql://"
                                    + postgres.getHost()
                                    + ":"
                                    + postgres.getMappedPort(5432)
                                    + "/"
                                    + postgres.getDatabaseName(),
                            "cbt.database.roles.app-api.password=" + postgres.getPassword())
                    .applyTo(context);
            context.addApplicationListener(
                    event -> {
                        if (event instanceof ContextClosedEvent closed
                                && closed.getApplicationContext() == context) {
                            remove(root);
                        }
                    });
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot initialize runtime test inputs", failure);
        }
    }

    private static void remove(Path root) {
        try {
            for (String name : new String[] {"hash", "memory.max", "memory.current", "draining"}) {
                Files.deleteIfExists(root.resolve(name));
            }
            Files.deleteIfExists(root);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot remove runtime test inputs", failure);
        }
    }
}
