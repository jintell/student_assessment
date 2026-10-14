package org.meldtech.platform.platform.infra.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.meldtech.platform.platform.infra.runtime.RuntimeRoleManifest.Route;
import org.springframework.boot.webflux.actuate.endpoint.web.AdditionalHealthEndpointPathsWebFluxHandlerMapping;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.config.WebFluxConfigurationSupport;
import org.springframework.web.reactive.function.server.support.RouterFunctionMapping;
import org.springframework.web.reactive.handler.AbstractUrlHandlerMapping;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;

final class BusinessRouteInventory {
    private static final String EMPTY_RESOURCE_MAPPING =
            WebFluxConfigurationSupport.class.getName() + "$EmptyHandlerMapping";

    private BusinessRouteInventory() {}

    static List<Route> inspect(ConfigurableApplicationContext context) {
        List<Route> routes = new ArrayList<>();
        for (HandlerMapping mapping : context.getBeansOfType(HandlerMapping.class).values()) {
            if (mapping instanceof RouterFunctionMapping functions) {
                var router = functions.getRouterFunction();
                if (router != null) {
                    routes.addAll(RegisteredRouteInspector.inspect(router));
                }
            } else if (mapping instanceof RequestMappingHandlerMapping annotated) {
                for (var info : annotated.getHandlerMethods().keySet()) {
                    var methods = info.getMethodsCondition().getMethods();
                    var patterns = info.getPatternsCondition().getPatterns();
                    if (methods.size() != 1 || patterns.size() != 1) {
                        throw invalid();
                    }
                    routes.add(
                            new Route(
                                    methods.iterator().next().name(),
                                    patterns.iterator().next().getPatternString()));
                }
            } else if (mapping instanceof AbstractUrlHandlerMapping urls) {
                if (!urls.getHandlerMap().isEmpty()) {
                    throw invalid();
                }
            } else if (!isEmptySupportMapping(mapping)) {
                throw RuntimeRoleManifest.invalid(
                        "business route inventory contains an opaque mapping: "
                                + mapping.getClass().getName());
            }
        }
        return routes.stream()
                .sorted(Comparator.comparing(Route::method).thenComparing(Route::path))
                .toList();
    }

    private static IllegalStateException invalid() {
        return RuntimeRoleManifest.invalid("business route inventory contains an opaque mapping");
    }

    private static boolean isEmptySupportMapping(HandlerMapping mapping) {
        // Boot registers this empty health mapping even when health lives on a separate port.
        return mapping.getClass().getName().equals(EMPTY_RESOURCE_MAPPING)
                || (mapping.getClass() == AdditionalHealthEndpointPathsWebFluxHandlerMapping.class
                        && ((AdditionalHealthEndpointPathsWebFluxHandlerMapping) mapping)
                                .getHandlerMethods()
                                .isEmpty());
    }
}
