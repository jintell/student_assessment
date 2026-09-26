package org.meldtech.platform.platform.infra.idempotency;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.meldtech.platform.shared.api.IdempotencyMechanism;
import org.meldtech.platform.shared.api.IdempotencyPolicy;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.meldtech.platform.shared.api.RouteDescriptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

@Component
final class IdempotencyRouteRegistry {

    private final List<RegisteredRoute> routes;

    IdempotencyRouteRegistry(List<PolicyProtectedRoute> routes) {
        IdempotencyRoutePolicyValidator.verify(routes);
        this.routes =
                routes.stream()
                        .map(IdempotencyRouteRegistry::register)
                        .flatMap(Optional::stream)
                        .toList();
    }

    Optional<RouteDescriptor> redisHeaderRoute(HttpMethod method, String path) {
        PathContainer requestPath = PathContainer.parsePath(path);
        return routes.stream()
                .filter(route -> route.descriptor().method().equals(method))
                .filter(route -> route.path().matches(requestPath))
                .filter(route -> route.policy().mechanism() == IdempotencyMechanism.REDIS_HEADER)
                .map(RegisteredRoute::descriptor)
                .findFirst();
    }

    private static Optional<RegisteredRoute> register(PolicyProtectedRoute route) {
        Objects.requireNonNull(route, "route");
        IdempotencyPolicy policy = route.getClass().getAnnotation(IdempotencyPolicy.class);
        if (policy == null) {
            return Optional.empty();
        }
        RouteDescriptor descriptor = route.descriptor();
        return Optional.of(
                new RegisteredRoute(
                        descriptor,
                        PathPatternParser.defaultInstance.parse(descriptor.pathTemplate()),
                        policy));
    }

    private record RegisteredRoute(
            RouteDescriptor descriptor, PathPattern path, IdempotencyPolicy policy) {}
}
