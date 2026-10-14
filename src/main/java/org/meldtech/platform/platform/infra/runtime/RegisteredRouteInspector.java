package org.meldtech.platform.platform.infra.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RequestPredicate;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;

/** Rejects opaque routing and verifies the registered predicate, not just its declaration. */
final class RegisteredRouteInspector implements RouterFunctions.Visitor {
    private final List<RuntimeRoleManifest.Route> routes = new ArrayList<>();

    static List<RuntimeRoleManifest.Route> inspect(RouterFunction<?> route) {
        var visitor = new RegisteredRouteInspector();
        route.accept(visitor);
        return List.copyOf(visitor.routes);
    }

    static void verify(PolicyProtectedRoute route) {
        var visitor = new RegisteredRouteInspector();
        route.accept(visitor);
        var descriptor = route.descriptor();
        if (!visitor.routes.equals(
                List.of(
                        new RuntimeRoleManifest.Route(
                                descriptor.method().name(), descriptor.pathTemplate())))) {
            throw RuntimeRoleManifest.invalid("registered route disagrees with descriptor");
        }
    }

    @Override
    public void route(RequestPredicate predicate, HandlerFunction<?> handler) {
        var visitor = new PredicateInspector();
        predicate.accept(visitor);
        if (visitor.methods.size() != 1 || visitor.paths.size() != 1) {
            throw unsupported();
        }
        routes.add(
                new RuntimeRoleManifest.Route(
                        visitor.methods.getFirst(), visitor.paths.getFirst()));
    }

    @Override
    public void startNested(RequestPredicate predicate) {
        throw unsupported();
    }

    @Override
    public void endNested(RequestPredicate predicate) {
        throw unsupported();
    }

    @Override
    public void resources(Function<ServerRequest, Mono<Resource>> lookup) {
        throw unsupported();
    }

    @Override
    public void attributes(Map<String, Object> attributes) {}

    @Override
    public void unknown(RouterFunction<?> router) {
        throw unsupported();
    }

    private static IllegalStateException unsupported() {
        return RuntimeRoleManifest.invalid("route predicate requires explicit manifest support");
    }

    private static final class PredicateInspector implements RequestPredicates.Visitor {
        private final List<String> methods = new ArrayList<>();
        private final List<String> paths = new ArrayList<>();

        @Override
        public void method(Set<HttpMethod> values) {
            values.forEach(value -> methods.add(value.name()));
        }

        @Override
        public void path(String pattern) {
            paths.add(pattern);
        }

        // Still required by Spring's Visitor; reject legacy predicates.
        @Override
        @SuppressWarnings("removal")
        public void pathExtension(String extension) {
            throw unsupported();
        }

        @Override
        public void header(String name, String value) {}

        @Override
        public void queryParam(String name, String value) {}

        @Override
        public void version(String version) {}

        @Override
        public void startAnd() {}

        @Override
        public void and() {}

        @Override
        public void endAnd() {}

        @Override
        public void startOr() {
            throw unsupported();
        }

        @Override
        public void or() {
            throw unsupported();
        }

        @Override
        public void endOr() {
            throw unsupported();
        }

        @Override
        public void startNegate() {
            throw unsupported();
        }

        @Override
        public void endNegate() {
            throw unsupported();
        }

        @Override
        public void unknown(RequestPredicate predicate) {
            throw unsupported();
        }
    }
}
