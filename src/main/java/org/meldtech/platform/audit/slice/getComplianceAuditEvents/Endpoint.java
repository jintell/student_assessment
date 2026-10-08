package org.meldtech.platform.audit.slice.getComplianceAuditEvents;

import java.util.Objects;
import org.meldtech.platform.shared.api.PolicyDecision;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.meldtech.platform.shared.api.PolicyResolver;
import org.meldtech.platform.shared.api.RouteDescriptor;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

public final class Endpoint implements PolicyProtectedRoute {

    public static final String ROUTE_ID = "audit.getComplianceAuditEvents";
    public static final String PATH = "/api/v1/audit-events";
    private final PolicyResolver policyResolver;
    private final Handler handler;
    private final RouterFunction<ServerResponse> route;

    public Endpoint(PolicyResolver policyResolver, Handler handler) {
        this.policyResolver = Objects.requireNonNull(policyResolver, "policyResolver");
        this.handler = Objects.requireNonNull(handler, "handler");
        route = RouterFunctions.route(RequestPredicates.GET(PATH), this::handle);
    }

    @Override
    public RouteDescriptor descriptor() {
        return RouteDescriptor.tenant(ROUTE_ID, HttpMethod.GET, PATH, "audit");
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
                        context.<ActorContext>getOrEmpty(ActorContext.class)
                                .map(actor -> authorize(actor, Request.from(serverRequest)))
                                .orElseGet(
                                        () ->
                                                ServerResponse.status(HttpStatus.FORBIDDEN)
                                                        .cacheControl(CacheControl.noStore())
                                                        .build()));
    }

    private Mono<ServerResponse> authorize(ActorContext actor, Request request) {
        return policyResolver
                .evaluate(ROUTE_ID, actor, request)
                .flatMap(
                        decision ->
                                decision == PolicyDecision.ALLOW
                                        ? handler.handle(actor, request)
                                        : Mono.error(
                                                new AccessDeniedException(
                                                        "Policy denied the operation")))
                .flatMap(
                        response ->
                                ServerResponse.ok()
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .cacheControl(CacheControl.noStore())
                                        .bodyValue(response));
    }
}
