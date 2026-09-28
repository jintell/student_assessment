package org.meldtech.platform.conformance.fixtures.actor.noarg;

import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.meldtech.platform.shared.api.RouteDescriptor;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

public final class Endpoint implements PolicyProtectedRoute {

    @Override
    public RouteDescriptor descriptor() {
        return RouteDescriptor.tenant("fixture.noArgWrite", HttpMethod.POST, "/fixture", "fixture");
    }

    @Override
    public Mono<HandlerFunction<ServerResponse>> route(ServerRequest request) {
        return Mono.empty();
    }

    @Override
    public void accept(RouterFunctions.Visitor visitor) {}
}
