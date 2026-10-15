package org.meldtech.platform.platform.infra.runtime;

import java.util.List;
import org.meldtech.platform.platform.api.RoleScoped;
import org.meldtech.platform.platform.infra.runtime.RuntimeRoleManifest.Route;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@RoleScoped("pindist")
final class PindistRouteValidator implements SmartInitializingSingleton {
    static final String FEATURE = "FEAT-EXAM-003";
    static final String PATH = "/api/v1/exam-sessions/{id}/pin-distribution";
    private final ConfigurableApplicationContext context;

    PindistRouteValidator(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<Route> routes;
        try {
            routes = BusinessRouteInventory.inspect(context);
        } catch (IllegalStateException exception) {
            throw invalid();
        }
        validate(routes, FeatureDeliveryStatus.load().contains(FEATURE));
    }

    static void validate(List<Route> routes, boolean delivered) {
        List<Route> expected = delivered ? List.of(new Route("GET", PATH)) : List.of();
        if (!routes.equals(expected)) {
            throw invalid();
        }
    }

    private static IllegalStateException invalid() {
        return RuntimeRoleManifest.invalid("pindist business route allowlist violated");
    }
}
