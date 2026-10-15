package org.meldtech.platform.platform.infra.runtime;

import java.util.Arrays;
import java.util.Map;
import org.meldtech.platform.platform.api.RuntimeRole;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Runs after profile expansion but before Boot initializes application logging. */
public final class RuntimeRoleEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        // The same artifact also has a non-serving migration bootstrap with no runtime role.
        // The runtime context's role assertion still rejects an unset role before beans start.
        if (Arrays.stream(RuntimeRole.values())
                .noneMatch(
                        role ->
                                Arrays.asList(environment.getActiveProfiles())
                                        .contains(role.profile()))) {
            return;
        }
        var role = RuntimeRoleConfiguration.resolve(environment);
        environment
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "validatedRuntimeLogRole",
                                Map.of(
                                        "logging.structured.json.add.role",
                                        role.name(),
                                        "logging.pattern.level",
                                        "[role:"
                                                + role.name()
                                                + "] "
                                                + environment.getProperty(
                                                        "logging.pattern.level", "%5p"))));
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
