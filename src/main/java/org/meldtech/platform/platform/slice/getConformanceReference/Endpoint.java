package org.meldtech.platform.platform.slice.getConformanceReference;

import org.meldtech.platform.shared.api.PolicyDecision;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.meldtech.platform.shared.api.PolicyResolver;
import org.meldtech.platform.shared.api.RequestCarrier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Component
final class Endpoint implements PolicyProtectedRoute {

    static final String ROUTE_ID = "platform.getConformanceReference";
    static final String PATH = "/api/v1/platform/conformance-reference";

    private final PolicyResolver policyResolver;
    private final Handler handler;
    private final RouterFunction<ServerResponse> route;

    Endpoint(PolicyResolver policyResolver, Handler handler) {
        this.policyResolver = policyResolver;
        this.handler = handler;
        route = RouterFunctions.route(RequestPredicates.GET(PATH), this::handle);
    }

    @Override
    public String routeId() {
        return ROUTE_ID;
    }

    @Override
    public Mono<HandlerFunction<ServerResponse>> route(ServerRequest request) {
        return route.route(request);
    }

    @Override
    public void accept(RouterFunctions.Visitor visitor) {
        route.accept(visitor);
    }

    private Mono<ServerResponse> handle(ServerRequest serverRequest) {
        return Mono.deferContextual(
                context ->
                        context.<RequestCarrier>getOrEmpty(RequestCarrier.class)
                                .map(this::authorizeAndHandle)
                                .orElseGet(
                                        () ->
                                                ServerResponse.status(HttpStatus.FORBIDDEN)
                                                        .cacheControl(CacheControl.noStore())
                                                        .build()));
    }

    private Mono<ServerResponse> authorizeAndHandle(RequestCarrier carrier) {
        return policyResolver
                .evaluate(ROUTE_ID, carrier, Request.INSTANCE)
                .flatMap(
                        decision ->
                                decision == PolicyDecision.ALLOW
                                        ? success(carrier)
                                        : forbidden(carrier.correlationId()));
    }

    private Mono<ServerResponse> success(RequestCarrier carrier) {
        return handler.handle(carrier, Request.INSTANCE)
                .flatMap(
                        response ->
                                ServerResponse.ok()
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .cacheControl(CacheControl.noStore())
                                        .bodyValue(response));
    }

    private static Mono<ServerResponse> forbidden(String correlationId) {
        return ServerResponse.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore())
                .bodyValue(
                        new DeniedProblem(
                                HttpStatus.FORBIDDEN.value(), "ACCESS_DENIED", correlationId));
    }

    private record DeniedProblem(int status, String code, String correlationId) {}
}
