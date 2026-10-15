package org.meldtech.platform.platform.infra.runtime;

import org.meldtech.platform.platform.api.RoleScoped;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@RoleScoped("worker")
final class WorkerHttpSurfaceValidator implements SmartInitializingSingleton {
    private final ConfigurableApplicationContext context;
    private final Environment environment;

    WorkerHttpSurfaceValidator(ConfigurableApplicationContext context, Environment environment) {
        this.context = context;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validateConfiguration(environment);
        if (!BusinessRouteInventory.inspect(context).isEmpty()) {
            throw invalid("worker business routes must be empty");
        }
    }

    static void validateConfiguration(Environment environment) {
        require(environment, "server.port", "-1");
        require(environment, "management.server.port", "9091");
        require(environment, "management.endpoints.enabled-by-default", "false");
        require(environment, "management.endpoints.web.base-path", "/");
        require(environment, "management.endpoints.web.path-mapping.health", "health");
        require(environment, "management.endpoints.web.exposure.include", "health");
        require(environment, "management.endpoints.web.discovery.enabled", "false");
        require(environment, "management.endpoint.health.enabled", "true");
        require(environment, "management.endpoint.health.show-details", "never");
        require(environment, "management.endpoint.health.show-components", "never");
    }

    private static void require(Environment environment, String property, String expected) {
        if (!expected.equals(environment.getProperty(property))) {
            throw invalid("invalid " + property);
        }
    }

    private static IllegalStateException invalid(String detail) {
        return RuntimeRoleManifest.invalid("worker HTTP surface: " + detail);
    }
}
